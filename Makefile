# Makefile variables set automatically
plugin_id=`cat plugin.json | python -c "import sys, json; print(str(json.load(sys.stdin)['id']).replace('/',''))"`
plugin_version=`cat plugin.json | python -c "import sys, json; print(str(json.load(sys.stdin)['version']).replace('/',''))"`
archive_file_name="dss-plugin-${plugin_id}-${plugin_version}.zip"
remote_url=`git config --get remote.origin.url`
last_commit_id=`git rev-parse HEAD`

.DEFAULT_GOAL := plugin

plugin: clean build-java
	@echo "[START] Archiving plugin to dist/ folder..."
	@cat plugin.json | json_pp > /dev/null
	@mkdir dist
	@echo "{\"remote_url\":\"${remote_url}\",\"last_commit_id\":\"${last_commit_id}\"}" > release_info.json
	@git archive -v -9 --format zip -o dist/${archive_file_name} HEAD
	@if [[ -d tests ]]; then \
		zip --delete dist/${archive_file_name} "tests/*"; \
	fi
	@zip -u dist/${archive_file_name} release_info.json
	@rm release_info.json
	@echo "[SUCCESS] Archiving plugin to dist/ folder: Done!"

dev: clean build-java
	@echo "[START] Archiving plugin to dist/ folder... (dev mode)"
	@cat plugin.json | json_pp > /dev/null
	@mkdir dist
	@zip -v -9 dist/${archive_file_name} -r . --exclude "tests/*" "env/*" ".git/*" ".pytest_cache/*" ".idea/*" "dist/*"
	@echo "[SUCCESS] Archiving plugin to dist/ folder: Done!"

build-java:
	@echo "[START] Building Java..."
	@ant jar
	@echo "[SUCCESS] Building Java: Done!"

clean:
	rm -rf dist
	rm -rf java-build
