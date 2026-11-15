.PHONY: ci prepare-s3-fixture
prepare-s3-fixture:
	@if test -f backend/file-storage-provider-s3/pom.xml; then bash scripts/prepare-s3-fixture.sh; fi

ci: prepare-s3-fixture
	python3 scripts/check-foundation.py
	python3 scripts/check-repository-hygiene.py
	python3 scripts/check-pom-format.py
	python3 scripts/check-domain-boundaries.py
	mvn -B -ntp --strict-checksums -f backend/pom.xml clean verify
