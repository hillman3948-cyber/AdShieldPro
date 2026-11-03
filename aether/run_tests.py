#!/usr/bin/env python3
"""Run the complete AETHER AI test suite (unit + security + HTTP integration).

Usage: .venv/bin/python aether/run_tests.py
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

if __name__ == "__main__":
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    for module in ("tests.test_core", "tests.test_api"):
        suite.addTests(loader.loadTestsFromName(module))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print("\n" + "=" * 72)
    print(f"AETHER test result: {result.testsRun} tests, "
          f"{len(result.failures)} failures, {len(result.errors)} errors")
    print("=" * 72)
    sys.exit(0 if result.wasSuccessful() else 1)
