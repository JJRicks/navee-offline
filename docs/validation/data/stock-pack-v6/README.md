# Standard-charger pack model (v6) evidence

`fit_pack_model.py` fits estimator `ut5-stock-pack-v6` from the anonymized measurements already
published in [stock-startup-v5](../stock-startup-v5/README.md). No new personal data is added here.
It writes:

- **Model constants:** the generated block in `src/com/jjricks/volt/Ut5MaxStockPackModel.java`.
- **Parity table:** `tests/StockPackParity.java`, which shows that the Java model reproduces this script.
- **[evaluation.json](evaluation.json):** leave-one-charge-out, mid-charge-connect and in-sample results.

```sh
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py           # fit and summarize
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --check   # verify committed outputs (CI)
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --write   # regenerate them
```

The script needs Python 3.8+ and nothing else. Methods, results and limits are in
[stock-pack-estimates.md](../../stock-pack-estimates.md).
