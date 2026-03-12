# brailleai-vision tests

## Test type
- Unit tests for vision-to-Braille conversion components.

## What is covered
- Cell building from normalized dots (`BrailleCellBuilder`).
- Cell encoding and text assembly (`BrailleUnicodeEncoder`, `BrailleUnicodeAssembler`).
- Defensive behavior in dot normalization (`DotNormalizer`).

## What is not covered
- Live calls to the external Python vision service.

## Run
```bash
mvn -f brailleai-vision/pom.xml test
```
