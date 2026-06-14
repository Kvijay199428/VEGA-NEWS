#!/usr/bin/env python3

import json
import requests
import logging
import time
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

# ------------------------------------------------------------------
# CONFIGURATION & PATH RESOLUTION
# ------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).parent.resolve()

API_BASE_URL = "http://localhost:8080/api/news/instrument"
INSTRUMENT_FILE = SCRIPT_DIR / "data/instruments/upstox/upstox.json"
HISTORY_DIR = SCRIPT_DIR / "storage/collector/news"
LOG_DIR = SCRIPT_DIR / "logs"
LOG_FILE = LOG_DIR / "collector.log"

MAX_WORKERS = 5
FETCH_INTERVAL_HOURS = 1  # Fetch news every 1 hour
REQUEST_TIMEOUT = 60

# Ensure directories exist
LOG_DIR.mkdir(parents=True, exist_ok=True)
HISTORY_DIR.mkdir(parents=True, exist_ok=True)

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
        self.history_path = HISTORY_DIR
        self.history_path.mkdir(parents=True, exist_ok=True)
        
    def load_instruments(self):
        logger.info(f"Loading instruments from {INSTRUMENT_FILE}...")
        try:
            with open(INSTRUMENT_FILE, "r", encoding="utf-8") as f:
                instruments = json.load(f)
            logger.info(f"Loaded {len(instruments)} instruments")
            return instruments
        except Exception as e:
            logger.error(f"Failed to load instruments: {e}")
            return []

    def get_fno_equities(self, instruments):
        logger.info("Filtering for F&O linked equities...")
        
        # 1. Collect F&O underlying keys
        fno_underlyings = {
            item["underlying_key"]
            for item in instruments
            if item.get("segment") == "NSE_FO"
            and item.get("underlying_type") == "EQUITY"
            and item.get("underlying_key")
        }
        
        # 2. Map back to NSE_EQ ISINs
        fno_equities = {}
        for item in instruments:
            if (item.get("segment") == "NSE_EQ" and 
                item.get("instrument_key") in fno_underlyings and 
                item.get("isin")):
                
                isin = item["isin"]
                fno_equities[isin] = {
                    "isin": isin,
                    "symbol": item.get("trading_symbol"),
                    "name": item.get("name")
                }
                
        logger.info(f"Found {len(fno_equities)} unique F&O equities")
        return fno_equities

    def should_fetch(self, isin):
        metadata_file = self.history_path / isin / "metadata.json"
        if not metadata_file.exists():
            return True
            
        try:
            with open(metadata_file, "r") as f:
                meta = json.load(f)
                last_fetch_str = meta.get("last_fetch")
                if not last_fetch_str:
                    return True
                    
                last_fetch = datetime.fromisoformat(last_fetch_str.replace("Z", "+00:00"))
                # Use timezone-aware comparison
                if datetime.now(timezone.utc) - last_fetch > timedelta(hours=FETCH_INTERVAL_HOURS):
                    return True
        except Exception as e:
            logger.warning(f"Error reading metadata for {isin}: {e}")
            return True
            
        return False

    def trigger_fetch(self, record):
        isin = record["isin"]
        symbol = record["symbol"]
        
        if not self.should_fetch(isin):
            logger.debug(f"Skipping {symbol} ({isin}) - recently fetched")
            return "SKIPPED"

        url = f"{API_BASE_URL}/{isin}"
        
        try:
            response = requests.get(url, timeout=REQUEST_TIMEOUT)
            response.raise_for_status()

            # Create ISIN directory for metadata
            isin_dir = self.history_path / isin
            isin_dir.mkdir(parents=True, exist_ok=True)
            
            # Update metadata
            metadata = {
                "isin": isin,
                "symbol": symbol,
                "name": record["name"],
                "last_fetch": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            }
            with open(isin_dir / "metadata.json", "w") as f:
                json.dump(metadata, f, indent=2)
                
            logger.info(f"Successfully triggered news fetch for {symbol} ({isin})")
            return "SUCCESS"
            
        except Exception as e:
            logger.error(f"Failed to fetch {symbol} ({isin}): {e}")
            return "FAILED"

    def run(self):
        logger.info("=== Starting F&O News Collection ===")
        instruments = self.load_instruments()
        if not instruments:
            return
            
        fno_equities = self.get_fno_equities(instruments)
        
        success = 0
        failed = 0
        skipped = 0
        
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            futures = {executor.submit(self.trigger_fetch, rec): rec for rec in fno_equities.values()}
            
            for future in as_completed(futures):
                result = future.result()
                if result == "SUCCESS": success += 1
                elif result == "FAILED": failed += 1
                elif result == "SKIPPED": skipped += 1
                
        logger.info("=== Collection Summary ===")
        logger.info(f"Total processed: {len(fno_equities)}")
        logger.info(f"Success: {success}")
        logger.info(f"Failed:  {failed}")
        logger.info(f"Skipped: {skipped}")
        logger.info("============================")

if __name__ == "__main__":
    collector = NewsCollector()
    collector.run()
