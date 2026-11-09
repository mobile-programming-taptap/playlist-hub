# TapTap 기여 가이드

`main`은 팀이 실행하고 시연하는 기준 브랜치입니다. 작업마다 최신 `main`에서 브랜치를 만들고, 하나의 기능이나 버그 수정 단위로 PR을 올립니다.

## 작업 시작

1. [프로젝트 명세](https://app.notion.com/p/3ed64b5ce98380b8b96ad80157bff1dd)와 관련 작업을 확인합니다. 같은 화면이나 API를 동시에 수정할 예정이라면 범위와 담당을 먼저 맞춥니다.
2. 저장소를 처음 받는다면 `git clone https://github.com/mobile-programming-taptap/playlist-hub.git`을 실행합니다. 저장소 폴더에서 아래 명령으로 작업을 시작합니다.

```sh
git switch main
git pull --ff-only origin main
git switch -c fix/recommendation-retry
```

브랜치 이름에는 목적을 담습니다. 예를 들어 기능은 `feat/playlist-import`, 버그 수정은 `fix/recommendation-retry`, 문서는 `docs/setup-guide`를 사용합니다. 서로 의존하지 않는 작업은 각각 `main`에서 분기합니다.

## 검사와 커밋

[README](README.md)에서 Android SDK와 서버 실행 방법을 확인합니다. 변경 범위에 맞는 명령을 저장소 루트에서 실행합니다.

```sh
(cd backend && ../android/gradlew test)
(cd android && ./gradlew :app:assembleDebug :app:lintDebug)
```

- 화면을 바꿨다면 에뮬레이터에서 사용자가 누르는 순서대로 확인합니다.
- 추천·외부 연동을 바꿨다면 필요한 실서비스 검사를 임시 계정이나 테스트 데이터로 실행합니다. 모의 응답 검사와 실제 API 호출 결과를 PR에 구분해 적습니다.
- `git status --short`와 `git diff`를 확인하고 필요한 파일만 스테이징합니다. API 키, `.env`, `local.properties`, 개인 DB, APK·JAR, 실행 로그는 커밋 대상에서 제외합니다.
- 커밋은 변경 목적별로 나눕니다. 예를 들어 `fix(ai): preserve retry after lookup failure`처럼 수정한 동작을 제목에 적습니다.

## PR 올리기

1. 작업 브랜치에서 `git push -u origin HEAD`를 실행합니다.
2. GitHub에서 PR을 만들고 **base는 `main`**, **compare는 작업 브랜치**로 지정합니다.
3. 본문에 변경 이유, 사용자가 체감하는 변경, 실행한 검사와 결과를 적습니다. UI 변경에는 화면 캡처나 짧은 영상을 첨부합니다. 확인하지 못한 환경이나 검사가 있으면 함께 적습니다.
4. GitHub Actions의 `checks`가 통과한 뒤 병합합니다. CI는 서버·DB 검사와 Android 빌드·lint를 확인합니다. 계측 테스트 APK는 빌드하며, 에뮬레이터 실행과 외부 API 실연동 결과는 별도로 확인합니다.
5. 병합 후 GitHub에서 작업 브랜치를 삭제합니다. 다음 작업은 다시 최신 `main`에서 시작합니다.
