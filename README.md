# TapTap

Java Android 음악 추천·플레이리스트 앱. 2026-10-03 최신 [노션 명세](https://app.notion.com/p/3ed64b5ce98380b8b96ad80157bff1dd)의 포함 결정 기능 F-01~F-06을 구현한다.

## 실행

Android Studio에서 `android/`를 연다. `core/`, `catalog/`는 같은 상위 폴더에 두어야 한다. Android 7.0 이상에서 설치 가능하다. 첫 실행은 키 없이 사용할 수 있는 **기본 추천**이다. 40곡의 검증된 카탈로그로 조건을 해석하고 반응·목록을 기기의 SQLite에 보관한다. 앱 삭제 시 기기 데이터도 삭제된다.

```sh
cd android
./gradlew :app:assembleDebug :core:checkRecommendations
```

백엔드는 Java 21 이상으로 실행한다. 기본 DB는 파일 H2이며 `backend/data/`에 보관된다. 기본 바인딩은 로컬 `127.0.0.1:8080`이다.

```sh
cd backend
../android/gradlew bootRun
```

앱 설정에서 `http://10.0.2.2:8080`을 입력하면 Android 에뮬레이터가 로컬 서버에 연결된다. 서버 연결 시 서버의 별도 반응·목록을 사용한다. 기기에 저장한 기본 추천 데이터와 자동 합치지 않는다. 연결을 해제하면 기기 데이터를 다시 볼 수 있다.

Android 17 이상에서 로컬 서버를 연결할 때 [로컬 네트워크 권한](https://developer.android.com/privacy-and-security/local-network-permission)을 요청한다. 거부하면 기본 추천을 사용할 수 있다. 공개 HTTPS 서버 연결에는 이 권한을 요청하지 않는다.

실제 휴대폰은 HTTPS 서버 주소를 사용한다. 개발 중 LAN 테스트만 `SERVER_ADDRESS=0.0.0.0`과 개발 PC의 사설 IP를 사용한다. HTTP 테스트 연결은 디버그 앱에서만 허용된다.

## OpenAI

서버 환경에 `OPENAI_API_KEY`를 설정한다. 키는 앱·저장소·로그에 넣지 않는다. 기본 모델은 `gpt-4o-mini`이며 `OPENAI_MODEL`로 바꿀 수 있다. OpenAI [Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)를 사용한다.

AI는 **사용자가 직접 작성한 500자 이하의 요청만** 입력받아 분위기·상황·장르·언어 조건 JSON으로 변환한다. 곡명·URL을 생성하지 않는다. 서버가 등록된 곡에서 선택하며 앱 내부 좋아요를 반영하고 싫어요 곡은 제외한다. 외부 곡 데이터·외부 열기 기록·저장 행동을 AI에 보내지 않는다. `store: false`로 요청한다.

키가 없거나 연결 실패·잘못된 응답·한도 초과가 발생하면 **기본 추천**으로 이어가고 화면에서 알린다. AI 호출은 사용자당 1시간 20회, 서버 전체 UTC 하루 200회를 기본 상한으로 삼으며 시도도 횟수에 포함한다. `AI_USER_HOURLY_LIMIT`, `AI_DAILY_LIMIT`로 조정한다. 실제 과금 상한은 OpenAI 프로젝트의 예산·권한 설정도 적용한다.

현재 환경에는 실제 OpenAI 키가 없으므로 실제 유료 호출은 미검증이다. 로컬 응답 서버로 실제 HTTP 요청 형식·출력 파싱·한도·기본 추천 복구를 검사한다.

## 저장·인증

서버는 앱 설치별 익명 세션을 발급한다. 32바이트 난수 토큰은 기기의 비공개 설정에, SHA-256 해시만 DB에 보관한다. 사용자 입력으로 소유자를 지정하지 않으며 모든 목록 접근을 토큰 소유자로 확인한다. 소셜 로그인·계정 복구·기기 간 계정 공유는 현재 범위에 포함하지 않는다. 앱을 삭제하면 세션 연결 정보도 삭제된다.

같은 목록에 같은 곡을 재시도해도 한 번만 저장한다. 목록 생성·조회·상세·곡 제거를 지원한다. 음원 파일은 저장하지 않는다. 이름 변경·목록 삭제·순서 변경·외부 목록 이전·긴 영상 변환·랭킹은 노션에서 미확정 또는 후순위이므로 포함하지 않았다.

목록에 추가할 때 곡 정보와 출처를 함께 보관하여 카탈로그가 변경되어도 저장한 곡 정보를 유지한다.

PostgreSQL을 사용할 때 `DATABASE_URL`을 JDBC URL로, `DATABASE_USER`, `DATABASE_PASSWORD`를 환경에 설정한다. 동일한 SQL 스키마를 사용한다. 현재 검증 DB는 H2이며 PostgreSQL 실연결은 미검증이다. 단일 서버 프로토타입으로 쓰기를 직렬 처리하며 여러 서버로 확장할 때 DB upsert·호출 한도 동시성 검토가 필요하다.

## 곡 데이터·재생

`catalog/tracks.json`: MusicBrainz 공식 API에서 조회한 실제 녹음 ID·곡명·아티스트·원본 링크. [핵심 메타데이터 CC0](https://musicbrainz.org/doc/About/Data_License)만 사용하고 분위기·상황 태그는 TapTap 샘플로 직접 작성했다. 사용자 태그·평점·앨범 표지·가사·음원은 가져오지 않는다. 샘플 태그는 추천 품질 평가가 완료된 객관적 속성으로 간주하지 않는다.

검증한 재생 링크가 있는 곡은 [공식 YouTube IFrame 플레이어](https://developers.google.com/youtube/iframe_api_reference)를 표시한다. 사용자가 플레이어의 재생을 눌러야 시작한다. 화면 이동·외부 앱 이동·백그라운드 진입 시 플레이어를 종료한다. 앱 ID를 `loadDataWithBaseURL` 출처로 사용해 [공식 클라이언트 식별 요구](https://developers.google.com/youtube/terms/required-minimum-functionality)를 적용한다. 곡의 공식 영상 링크가 없으면 검색으로 연결한다. 외부 Spotify 검색과 YouTube Music 영상·검색 연결을 구분한다. 지역·임베드 제한에 따른 재생은 대체 외부 열기로 복구한다.

카탈로그 재검증: `python3 tools/verify_catalog.py`. 이 작업은 명시적으로 실행할 때만 네트워크를 사용하며 MusicBrainz 요청 간격을 1초 이상 둔다. 동명 코멘터리·영어 버전·Live·Remix·Cover를 제외한다. YouTube oEmbed는 직접 고른 재생 링크의 존재 확인에만 사용하고 그 메타데이터를 추천 카탈로그에 등록하지 않는다.

## 검사

```sh
cd backend
../android/gradlew test bootJar

cd ../android
./gradlew :app:lintDebug :app:connectedDebugAndroidTest --no-configuration-cache
```

추천 조건·반응·출처 경계는 `RecommendationCheck`의 실행 가능한 assertion 검사로 확인한다. 서버 통신 검사는 사용자 소유권, 중복 저장, 재시작 보관, OpenAI JSON 계약과 호출 상한을 확인한다. Android 저장 검사는 별도 테스트 DB에서 중복 저장·반응 변경·취소·재열기 후 보관을 확인한다. 테스트 DB는 종료 시 삭제한다. 기기 검사는 에뮬레이터가 필요하며 Gradle이 검사 후 대상 앱을 제거할 수 있으므로 시연 전에 디버그 APK를 다시 설치한다.

공유 가능한 APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
실행 가능한 서버: `backend/build/libs/taptap-backend.jar`.
