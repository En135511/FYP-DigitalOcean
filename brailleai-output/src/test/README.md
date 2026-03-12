# brailleai-output tests

## Test type
- Unit tests for output-format generation logic.

## What is covered
- Unicode Braille to BRF ASCII conversion (`BrfGenerator`).
- Output routing by format (`FormatRouter`).

## What is not covered
- Binary-level content validation of full PDF/DOCX documents.

## Run
```bash
mvn -f brailleai-output/pom.xml test
```
