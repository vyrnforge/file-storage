.PHONY: ci
ci:
	python3 scripts/check-foundation.py
	python3 scripts/check-repository-hygiene.py
	python3 scripts/check-pom-format.py
	python3 scripts/check-domain-boundaries.py
	mvn -B -ntp --strict-checksums -f backend/pom.xml clean verify
