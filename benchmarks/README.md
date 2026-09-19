# Historical PR replay dataset

Use one JSON object per line with an immutable PR context and human-labelled expected findings. Remove credentials, customer data, and proprietary source that is not approved for evaluation before committing a dataset.

The included `sample-replay.jsonl` is a format/runner smoke fixture, not a historical benchmark. A report is considered representative only after at least 100 real, permissioned PR cases are loaded; smaller runs emit an explicit warning.

Run:

```bash
npm run benchmark -- benchmarks/your-history.jsonl
```
