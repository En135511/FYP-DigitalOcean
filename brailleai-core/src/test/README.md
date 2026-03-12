# brailleai-core tests

## Test type
- Unit tests for core pipeline logic.

## What is covered
- Validation rules (`BrailleValidator` and checkers).
- Normalization stages (`WhitespaceNormalizer`, `LineBreakNormalizer`, `UnicodeSanitizer`).
- Post-processing (`OutputCleaner`).
- Pipeline flow and translator override behavior.

## What is not covered
- Real Liblouis translation engine execution.

## Run
```bash
mvn -f brailleai-core/pom.xml test
```
