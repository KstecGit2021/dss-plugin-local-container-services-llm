# Makefile 변수 자동 설정
plugin_id=`cat plugin.json | python -c "import sys, json; print(str(json.load(sys.stdin)['id']).replace('/',''))"` # plugin.json 파일에서 플러그인 ID를 추출
plugin_version=`cat plugin.json | python -c "import sys, json; print(str(json.load(sys.stdin)['version']).replace('/',''))"` # plugin.json 파일에서 플러그인 버전을 추출
archive_file_name="dss-plugin-${plugin_id}-${plugin_version}.zip" # 플러그인 아카이브 파일 이름 설정
remote_url=`git config --get remote.origin.url` # Git 리모트 저장소 URL 가져오기
last_commit_id=`git rev-parse HEAD` # 마지막 커밋 ID 가져오기

.DEFAULT_GOAL := plugin # 기본 목표 설정

plugin: clean build-plugin zip-plugin # 'plugin' 목표 정의: clean, build-plugin, zip-plugin 순서로 실행

zip-plugin: 
    @echo "[START] Archiving plugin to dist/ folder..." # 아카이브 시작 메시지 출력
    @cat plugin.json | json_pp > /dev/null # plugin.json 파일을 포맷팅 (출력은 하지 않음)
    @mkdir dist # dist 디렉토리 생성
    @echo "{\"remote_url\":\"${remote_url}\",\"last_commit_id\":\"${last_commit_id}\"}" > release_info.json # release_info.json 파일 생성 및 리모트 URL과 마지막 커밋 ID 기록
    @zip -r dist/${archive_file_name} release_info.json plugin.json java-lib/* java-llms/* parameter-sets/* LICENSE README.md # 플러그인 관련 파일들을 dist 디렉토리에 아카이브
    @rm release_info.json # 임시로 생성한 release_info.json 파일 삭제
    @echo "[SUCCESS] Archiving plugin to dist/ folder: Done!" # 아카이브 완료 메시지 출력

build-plugin: 
    @echo "[START] Building Java..." # Java 빌드 시작 메시지 출력
    @ant jar # Ant를 사용해 Java 빌드
    @echo "[SUCCESS] Building Java: Done!" # Java 빌드 완료 메시지 출력

clean: 
    rm -rf dist # dist 디렉토리 삭제
    rm -rf java-build # java-build 디렉토리 삭제
