# tools/ml: offline ML tooling for go-psi

Python, managed by `uv`. Nothing here ships in the plugin; it produces corpora, datasets and model
files that the plugin later loads. See `docs/ML.md` for the plan.

```
uv sync                       # once
uv run pytest                 # tests
uv run ruff check . && uv run ruff format . && uv run pyright
```

All outputs go to `tools/ml/data/` (git-ignored).

## Corpus

Three sources, merged into one file catalogue `data/manifest.parquet`:

| source | what | license policy |
|---|---|---|
| `goroot` | `$GOROOT/src` (modules `std` and `cmd`) | BSD-3-Clause |
| `fetched` | modules from `data/modules.lock`, downloaded from proxy.golang.org | SPDX from deps.dev |
| `gomodcache` | highest version of each module in the local `GOMODCACHE` (opt-in) | license files at the module root |

Only permissive licenses are accepted (`licenses.PERMISSIVE`); an unrecognised or missing license
excludes the module. `vendor/`, `testdata/`, `.x`/`_x` directories and nested modules are skipped.
Generated files, cgo files, tests and content duplicates are kept but flagged, so each dataset
exporter decides for itself. The train/valid/test split (80/10/10) is a stable hash of the
module path (for GOROOT: of the package tree, e.g. `std/net`, `std/cmd/compile`).

### 1. Module list from deps.dev (BigQuery, once)

In the BigQuery console (sandbox is enough), find the latest snapshot:

```sql
SELECT Time FROM `bigquery-public-data.deps_dev_v1.Snapshots` ORDER BY Time DESC LIMIT 1
```

Use that timestamp literally below: the `*Latest` views filter through a subquery, which defeats
partition pruning and scans every snapshot (over the 1 TB free quota).

Create a dataset `gopsi` (location `US`), then materialise direct dependents (~535 GB scanned):

```sql
CREATE TABLE gopsi.direct_dependents AS
SELECT Name AS module, COUNT(DISTINCT Dependent.Name) AS direct_dependents
FROM `bigquery-public-data.deps_dev_v1.Dependents`
WHERE SnapshotAt = TIMESTAMP '<snapshot>'
  AND System = 'GO' AND Dependent.System = 'GO'
  AND MinimumDepth = 1 AND DependentIsHighestReleaseWithResolution
GROUP BY Name
HAVING direct_dependents >= 5
```

Then the export (~7 GB scanned), saved as `data/deps_dev_go_modules.csv`:

```sql
WITH latest AS (
  SELECT Name AS module, Version AS version, ARRAY_TO_STRING(Licenses, ' | ') AS licenses
  FROM `bigquery-public-data.deps_dev_v1.PackageVersions`
  WHERE SnapshotAt = TIMESTAMP '<snapshot>' AND System = 'GO'
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY Name ORDER BY VersionInfo.IsRelease DESC, VersionInfo.Ordinal DESC) = 1
),
stars AS (
  SELECT p.Name AS module, MAX(pr.StarsCount) AS stars, ANY_VALUE(pr.Name) AS repo
  FROM `bigquery-public-data.deps_dev_v1.PackageVersionToProject` p
  JOIN `bigquery-public-data.deps_dev_v1.Projects` pr
    ON pr.Type = p.ProjectType AND pr.Name = p.ProjectName
  WHERE p.SnapshotAt = TIMESTAMP '<snapshot>' AND pr.SnapshotAt = TIMESTAMP '<snapshot>'
    AND p.System = 'GO'
  GROUP BY p.Name
)
SELECT l.module, l.version, l.licenses,
       IFNULL(d.direct_dependents, 0) AS direct_dependents,
       IFNULL(s.stars, 0) AS stars, s.repo
FROM latest l
LEFT JOIN gopsi.direct_dependents d USING (module)
LEFT JOIN stars s USING (module)
WHERE IFNULL(d.direct_dependents, 0) >= 5 OR IFNULL(s.stars, 0) >= 200
ORDER BY direct_dependents DESC, stars DESC
```

### 2. Select, fetch, catalogue

```
uv run gopsi-corpus select   [--top-dependents 2000] [--top-stars 1000] [--exclude REGEX]
uv run gopsi-corpus fetch    [--jobs 8] [--max-zip-mb 200] [--limit N]
uv run gopsi-corpus manifest [--with-gomodcache] [--no-goroot]
```

- `select` writes `data/modules.lock` (`module,version,licenses,direct_dependents,stars,reason`):
  `golang.org/x/*`, then the top libraries by direct dependents, then the top applications by
  stars. The lock pins the corpus; keep it alongside any published dataset.
- `fetch` downloads `<module>/@v/<version>.zip` from the proxy (no `go` binary needed) into
  `data/modules/` with the GOMODCACHE layout, keeping only `.go`, `go.mod`, `go.sum` and license
  files. It is resumable: finished modules carry `.gopsi-module.json` and are skipped.
  `data/modules/fetch-report.csv` lists the status of every module.
- `manifest` writes `data/manifest.parquet`. Inspect it with duckdb, e.g.

```
duckdb -c "select source, split, count(*) from 'data/manifest.parquet' where not is_generated and duplicate_of is null group by all"
```
