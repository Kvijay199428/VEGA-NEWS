#!/usr/bin/env python3

import json
import requests
import logging
import time
import os
import hashlib
from datetime import datetime, timedelta, timezone
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

# ------------------------------------------------------------------
# CONFIGURATION & PATH RESOLUTION
# ------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()

INSTRUMENT_FILE = SCRIPT_DIR / "data/instruments/upstox/upstox.json"
AUTH_FILE = SCRIPT_DIR / "auth/upstox/auth.upstox.json"
STORAGE_DIR = SCRIPT_DIR / "storage/user/news"
HOLDINGS_RAW = SCRIPT_DIR / "storage/user/holdings/holdings.jsonl"
POSITIONS_RAW = SCRIPT_DIR / "storage/user/positions/positions.jsonl"
LOG_DIR = SCRIPT_DIR / "logs"
LOG_FILE = LOG_DIR / "collector.log"

MAX_WORKERS = 5
FETCH_INTERVAL_HOURS = 1  # Fetch F&O news every 1 hour
REQUEST_TIMEOUT = 30

UPSTOX_API_URL = "https://api.upstox.com/v2/news"

# Ensure directories exist
LOG_DIR.mkdir(parents=True, exist_ok=True)
(STORAGE_DIR / "instruments").mkdir(parents=True, exist_ok=True)
(STORAGE_DIR / "metadata").mkdir(parents=True, exist_ok=True)
(STORAGE_DIR / "state").mkdir(parents=True, exist_ok=True)

# ------------------------------------------------------------------
# LOGGING SETUP
# ------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# ------------------------------------------------------------------
# COLLECTOR CLASS
# ------------------------------------------------------------------
class NewsCollector:
    def __init__(self):
        self.instruments_data = self.load_instruments()
        self.fno_equities = self.get_fno_equities(self.instruments_data)
        self.token = self.load_token()

    def load_token(self):
        try:
            with open(AUTH_FILE, "r") as f:
                data = json.load(f)
                return data.get("accounts", {}).get("analytic", {}).get("accessToken")
        except Exception as e:
            logger.error(f"Failed to load token: {e}")
            return None

    def load_instruments(self):
        try:
            with open(INSTRUMENT_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            logger.error(f"Failed to load instruments: {e}")
            return []

    def get_fno_equities(self, instruments):
        fno_underlyings = {
            item["underlying_key"]
            for item in instruments
            if item.get("segment") == "NSE_FO"
            and item.get("underlying_type") == "EQUITY"
            and item.get("underlying_key")
        }
        
        fno_equities = {}
        for item in instruments:
            if (item.get("segment") == "NSE_EQ" and 
                item.get("instrument_key") in fno_underlyings and 
                item.get("isin")):
                isin = item["isin"]
                fno_equities[isin] = {
                    "isin": isin,
                    "symbol": item.get("trading_symbol"),
                    "name": item.get("name"),
                    "instrument_key": item.get("instrument_key")
                }
        return fno_equities

    def read_portfolio(self):
        holdings = set()
        if HOLDINGS_RAW.exists():
            try:
                with open(HOLDINGS_RAW, "r") as f:
                    content = f.read().strip()
                    if content:
                        try:
                            # Try parsing as a single JSON object
                            data = json.loads(content)
                            items = data.get("data", [])
                        except json.JSONDecodeError:
                            # If it fails, try parsing as JSONL
                            items = []
                            for line in content.split('\n'):
                                if line.strip():
                                    try:
                                        line_data = json.loads(line)
                                        if "data" in line_data:
                                            items.extend(line_data["data"])
                                        else:
                                            items.append(line_data)
                                    except Exception:
                                        pass
                        for item in items:
                            if "isin" in item:
                                holdings.add(item["isin"])
            except Exception as e:
                logger.error(f"Failed to parse holdings: {e}")
        else:
            logger.warning(f"Holdings raw file missing at: {HOLDINGS_RAW}")

        positions = set()
        if POSITIONS_RAW.exists():
            try:
                with open(POSITIONS_RAW, "r") as f:
                    content = f.read().strip()
                    if content:
                        try:
                            data = json.loads(content)
                            items = data.get("data", [])
                        except json.JSONDecodeError:
                            items = []
                            for line in content.split('\n'):
                                if line.strip():
                                    try:
                                        line_data = json.loads(line)
                                        if "data" in line_data:
                                            items.extend(line_data["data"])
                                        else:
                                            items.append(line_data)
                                    except Exception:
                                        pass
                        for item in items:
                            qty = item.get("quantity", 0)
                            token = item.get("instrument_token", "")
                            if qty > 0 and "_EQ|" in token:
                                isin = token.split("|")[1]
                                positions.add(isin)
            except Exception as e:
                logger.error(f"Failed to parse positions: {e}")
        else:
            logger.warning(f"Positions raw file missing at: {POSITIONS_RAW}")
        
        return holdings, positions

    def load_snapshot(self, name):
        path = STORAGE_DIR / "state" / f"{name}_snapshot.json"
        if path.exists():
            try:
                with open(path, "r") as f:
                    return set(json.load(f))
            except Exception:
                pass
        return set()

    def save_snapshot(self, name, isins):
        path = STORAGE_DIR / "state" / f"{name}_snapshot.json"
        with open(path, "w") as f:
            json.dump(list(isins), f)

    def should_fetch_fno(self, isin):
        archive_file = STORAGE_DIR / "instruments" / f"{isin}.jsonl"
        if not archive_file.exists():
            return True
            
        meta_file = STORAGE_DIR / "metadata" / f"{isin}.json"
        if not meta_file.exists():
            return True
        try:
            with open(meta_file, "r") as f:
                meta = json.load(f)
                last_fetch_str = meta.get("last_fetch")
                if not last_fetch_str:
                    return True
                last_fetch = datetime.fromisoformat(last_fetch_str.replace("Z", "+00:00"))
                if datetime.now(timezone.utc) - last_fetch > timedelta(hours=FETCH_INTERVAL_HOURS):
                    return True
        except Exception:
            return True
        return False

    def generate_hash(self, article):
        link = article.get("article_link", "")
        if link:
            return hashlib.sha256(link.encode('utf-8')).hexdigest()
        heading = article.get("heading", "")
        summary = article.get("summary", "")
        return hashlib.sha256((heading + summary).encode('utf-8')).hexdigest()

    def fetch_and_store(self, isin, instrument_key=None):
        if not self.token:
            logger.error(f"ISIN={isin} ArchiveCreated=false Reason=MISSING_TOKEN")
            return "FAILED"

        # Lookup instrument key if not provided
        if not instrument_key:
            for item in self.instruments_data:
                if item.get("isin") == isin and item.get("segment") == "NSE_EQ":
                    instrument_key = item.get("instrument_key")
                    break
        
        if not instrument_key:
            logger.warning(f"ISIN={isin} ArchiveCreated=false Reason=INSTRUMENT_KEY_NOT_FOUND")
            return "SKIPPED"

        url = f"{UPSTOX_API_URL}?category=instrument_keys&instrument_keys={instrument_key}"
        headers = {"Accept": "application/json", "Authorization": f"Bearer {self.token}"}
        
        try:
            response = requests.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
            if response.status_code == 429:
                logger.warning(f"ISIN={isin} Reason=RATE_LIMIT_HIT, retrying...")
                time.sleep(1)
                response = requests.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
                
            if response.status_code != 200:
                logger.error(f"ISIN={isin} HTTP={response.status_code} ArchiveCreated=false Reason=HTTP_ERROR")
                return "FAILED"
                
            data = response.json()
            
            if data.get("status") != "success":
                logger.error(f"ISIN={isin} HTTP={response.status_code} ArchiveCreated=false Reason=API_STATUS_FAILURE")
                return "FAILED"
                
            items = data.get("data", {}).get(instrument_key, [])
            total_fetched = len(items)
            
            # Load existing hashes
            archive_file = STORAGE_DIR / "instruments" / f"{isin}.jsonl"
            existing_hashes = set()
            latest_time = 0
            total_articles = 0
            
            if archive_file.exists():
                with open(archive_file, "r") as f:
                    for line in f:
                        try:
                            art = json.loads(line)
                            h = art.get("sourceHash") or art.get("hash")
                            if h: existing_hashes.add(h)
                            pt = art.get("publishedTime", 0)
                            if pt > latest_time: latest_time = pt
                            total_articles += 1
                        except Exception:
                            continue
            
            new_articles = []
            for item in items:
                art = {
                    "isin": isin,
                    "instrumentKey": instrument_key,
                    "heading": item.get("heading", ""),
                    "summary": item.get("summary", ""),
                    "articleLink": item.get("article_link", ""),
                    "publishedTime": item.get("published_time", int(time.time()*1000)),
                }
                art["sourceHash"] = self.generate_hash(art)
                if art["sourceHash"] not in existing_hashes:
                    new_articles.append(art)
                    existing_hashes.add(art["sourceHash"])
                    if art["publishedTime"] > latest_time:
                        latest_time = art["publishedTime"]
            
            # Append new
            if new_articles:
                with open(archive_file, "a") as f:
                    for art in new_articles:
                        f.write(json.dumps(art) + "\n")
                total_articles += len(new_articles)
            
            # Ensure file exists even if 0 articles (Bug 1.3)
            if not archive_file.exists():
                archive_file.touch(exist_ok=True)
            
            # Verify actual article persistence
            if not archive_file.exists():
                logger.error(f"ISIN={isin} ArchiveCreated=false Reason=WRITE_FAILURE")
                return "FAILED"

            # Update Metadata
            meta_file = STORAGE_DIR / "metadata" / f"{isin}.json"
            meta = {
                "isin": isin,
                "totalArticles": total_articles,
                "latestPublishedTime": latest_time,
                "lastUpdated": int(time.time()*1000),
                "last_fetch": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            }
            with open(meta_file, "w") as f:
                json.dump(meta, f, indent=2)

            if total_fetched == 0:
                logger.info(f"ISIN={isin} HTTP=200 ArticlesReturned=0 ArchiveCreated=true Reason=NO_ARTICLES")
            else:
                logger.info(f"ISIN={isin} HTTP=200 ArticlesReturned={total_fetched} NewArticles={len(new_articles)} ArchiveCreated=true")
            return "SUCCESS"
            
        except Exception as e:
            logger.error(f"ISIN={isin} HTTP=Unknown ArchiveCreated=false Reason=EXCEPTION Message={e}")
            return "FAILED"


    def run(self):
        logger.info("=== Starting News Collection ===")
        
        # 1. Detect Portfolio Changes
        holdings, positions = self.read_portfolio()
        old_holdings = self.load_snapshot("holdings")
        old_positions = self.load_snapshot("positions")
        
        added_holdings = holdings - old_holdings
        added_positions = positions - old_positions
        added_isins = added_holdings | added_positions
        
        if added_isins:
            logger.info(f"Detected {len(added_isins)} new portfolio ISINs. Fetching news immediately...")
            for isin in added_isins:
                self.fetch_and_store(isin)
        
        self.save_snapshot("holdings", holdings)
        self.save_snapshot("positions", positions)
        
        # 2. Fetch F&O Equities
        to_fetch_fno = [rec for isin, rec in self.fno_equities.items() if self.should_fetch_fno(isin)]
        
        logger.info(f"Found {len(to_fetch_fno)} F&O equities requiring update.")
        success, failed, skipped = 0, 0, 0
        
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            futures = {executor.submit(self.fetch_and_store, rec["isin"], rec["instrument_key"]): rec for rec in to_fetch_fno}
            for future in as_completed(futures):
                result = future.result()
                if result == "SUCCESS": success += 1
                elif result == "FAILED": failed += 1
                elif result == "SKIPPED": skipped += 1
                
        logger.info("=== Collection Summary ===")
        logger.info(f"Success: {success}")
        logger.info(f"Failed:  {failed}")
        logger.info(f"Skipped: {skipped}")
        logger.info("============================")
        
        # 3. Build Views (Phase 8)
        self.build_views("holdings", holdings)
        self.build_views("positions", positions)

    def build_views(self, name, isins):
        logger.info(f"Building {name} view for {len(isins)} ISINs...")
        all_articles = []
        for isin in isins:
            archive_file = STORAGE_DIR / "instruments" / f"{isin}.jsonl"
            if archive_file.exists():
                try:
                    with open(archive_file, "r") as f:
                        for line in f:
                            line = line.strip()
                            if not line:
                                continue
                            try:
                                all_articles.append(json.loads(line))
                            except Exception:
                                continue
                except Exception as e:
                    logger.error(f"Failed to read archive for {isin}: {e}")
        
        # Sort by publishedTime descending
        all_articles.sort(key=lambda x: x.get("publishedTime", 0), reverse=True)
        
        out_file = STORAGE_DIR / f"{name}.jsonl"
        try:
            with open(out_file, "w") as f:
                for art in all_articles:
                    f.write(json.dumps(art) + "\n")
            logger.info(f"Successfully built {name} view with {len(all_articles)} articles.")
        except Exception as e:
            logger.error(f"Failed to write {name} view: {e}")

if __name__ == "__main__":
    collector = NewsCollector()
    collector.run()
