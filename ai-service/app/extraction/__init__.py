"""Laboratory report extraction (ADR 0003, ADR 0006, ADR 0007, ADR 0008).

The LLM only reads the layout: names, values, units and ranges exactly as printed, the
laboratory's own flag, and where each line is. Everything derived from those strings (numbers,
comparators, range bounds, populations, warnings) is computed here, deterministically, so the
model never invents a number. Nothing produced here is trusted until the patient reviews it.
"""
