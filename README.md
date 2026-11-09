# TapTap

자연어로 음악을 추천받고, 곡을 들어보거나 플레이리스트에 저장하는 Android 앱입니다. YouTube 영상의 설명·댓글 시간표를 곡 목록으로 변환하고 Spotify·YouTube Music 목록을 가져올 수 있습니다.

- [기여 가이드](CONTRIBUTING.md)에서 브랜치, 커밋, PR 절차를 확인하세요.
- [기능별 상태](docs/full-feature-status.md)와 [제품 가이드](docs/implementation.html)에서 구현 범위와 화면을 확인하세요.
- [검증 기록](docs/tests-and-evidence.md)에서 자동 검사, 실연동 결과, 남은 검증을 확인하세요.

## 로컬 실행

Android Studio에서 `android/`를 엽니다. Android SDK 37로 빌드하며 앱은 Android 7.0 이상에 설치할 수 있습니다. 백엔드는 Java 21 이상을 사용합니다.

저장소 루트에서 `backend/.env.example`을 `backend/.env`로 복사하고 필요한 값을 채웁니다. 기본 서버 주소는 `127.0.0.1:8080`이고, 기본 DB는 `backend/data/`의 파일 H2입니다.

```sh
(cd backend && ../android/gradlew bootRun)
```

별도 터미널에서 디버그 APK를 빌드합니다.

```sh
(cd android && ./gradlew :app:assembleDebug)
```

APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 로컬 에뮬레이터에서는 필요할 때 앱의 **설정 → 개발자 연결 설정**에서 `http://10.0.2.2:8080`을 지정합니다. Android 17 이상에서 로컬 서버에 연결할 때는 로컬 네트워크 권한을 허용합니다.

## 서비스 설정

- **AI 추천**은 서버에 `OPENAI_API_KEY`가 필요합니다. 기본 모델은 `gpt-5.4-mini`이며 `OPENAI_MODEL`로 지정할 수 있습니다. 서버 설정 변경 후 재시작합니다.
- **로그인·목록 연동** 설정은 [카카오 로그인](docs/kakao-login.md)과 [서비스 환경 설정](backend/.env.example)을 참고하세요. [영상 변환 사용법](docs/video-conversion.md)도 따로 정리돼 있습니다.
- **팀 테스트**는 [비공개 HTTPS 연결](docs/team-testing.md)을 따릅니다. 운영 환경의 PostgreSQL·Caddy·백업은 [배포 안내](docs/deployment.md)에 있습니다.

## 검사

```sh
(cd backend && ../android/gradlew test bootJar)
(cd android && ./gradlew :app:assembleDebug :app:lintDebug)
```

GitHub Actions는 PR과 `main` 푸시에서 서버 테스트, JAR 재시작, PostgreSQL, Android 빌드·lint를 확인합니다. 에뮬레이터 검사는 연결된 테스트 기기에서 `:app:connectedDebugAndroidTest`로 실행합니다. 설정과 검증 범위는 [CI 워크플로](.github/workflows/ci.yml)와 [검증 기록](docs/tests-and-evidence.md)을 참고하세요.
