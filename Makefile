.PHONY: all install test lint validate-schemas clean

all: install test

# Install all language packages
install: install-python install-node

install-python:
	cd packages/python && pip install -e ".[dev]"

install-node:
	cd packages/node && npm install

# Run all tests
test: test-python test-node test-java

test-python:
	cd packages/python && python -m pytest tests/ -v

test-node:
	cd packages/node && npm test

test-java:
	cd packages/java && mvn compile

# Lint
lint: lint-python lint-node

lint-python:
	cd packages/python && python -m ruff check dt3_sdk dt3_api

lint-node:
	cd packages/node && npx eslint "src/**/*.ts" "tests/**/*.ts"

# Schema validation
validate-schemas:
	python tools/schema-validator/validate_schemas.py

# Compatibility checks
check-contracts:
	python tools/compatibility-checker/check_contracts.py

# Clean
clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name node_modules -exec rm -rf {} + 2>/dev/null || true
	find . -type d -name .pytest_cache -exec rm -rf {} + 2>/dev/null || true
	cd packages/node && rm -rf dist/ 2>/dev/null || true
