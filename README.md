# TapTap

Java Android 음악 추천·플레이리스트 앱. 2026-10-03 최신 [노션 명세](https://app.notion.com/p/3ed64b5ce98380b8b96ad80157bff1dd)의 포함 결정 기능 F-01~F-06과 사용자가 추가 진행을 지시한 목록 관리·검색·추천 수정·텍스트 공유를 구현한다.

## 실행

Android Studio에서 `android/`를 연다. `core/`, `catalog/`는 같은 상위 폴더에 두어야 한다. Android 7.0 이상에서 설치 가능하다. 첫 실행은 키 없이 사용할 수 있는 **기본 추천**이다. 40곡의 검증된 카탈로그와 공식 재생 링크로 조건을 해석하고 반응·목록을 기기의 SQLite에 보관한다. 앱 삭제 시 기기 데이터도 삭제된다.

```sh
cd android
./gradlew :app:assembleDebug :core:checkRecommendations
```

백엔드는 Java 21 이상으로 실행한다. 기본 DB는 파일 H2이며 `backend/data/`에 보관된다. 기본 바인딩은 로컬 `127.0.0.1:8080`이다.

```sh
cd backend
../android/gradlew bootJar
java -jar build/libs/taptap-backend.jar
```

서버는 `backend/`를 현재 폴더로 실행한다. `.env`와 `data/`의 상대 경로가 이 폴더를 기준으로 정해진다. 개발 중 바로 실행하려면 같은 폴더에서 `../android/gradlew bootRun`을 사용할 수 있다.

앱 설정에서 `http://10.0.2.2:8080`을 입력하면 Android 에뮬레이터가 로컬 서버에 연결된다. 서버 연결 시 서버의 별도 반응·목록을 사용한다. 기기에 저장한 기본 추천 데이터와 자동 합치지 않는다. 연결을 해제하면 기기 데이터를 다시 볼 수 있다.

Android 17 이상에서 로컬 서버를 연결할 때 [로컬 네트워크 권한](https://developer.android.com/privacy-and-security/local-network-permission)을 요청한다. 거부하면 저장된 서버 주소를 해제하고 기본 추천을 사용할 수 있다. 서버 세션은 보관하여 다시 연결할 수 있다. 공개 HTTPS 서버 연결에는 이 권한을 요청하지 않는다.

실제 휴대폰은 HTTPS 서버 주소를 사용한다. 개발 중 LAN 테스트만 `SERVER_ADDRESS=0.0.0.0`과 개발 PC의 사설 IP를 사용한다. HTTP 테스트 연결은 디버그 앱에서만 허용된다.

## OpenAI

서버 환경에 `OPENAI_API_KEY`를 설정하거나 `backend/.env`에 `OPENAI_API_KEY=발급받은키` 한 줄을 저장한다. 서버를 `backend/`에서 실행하면 이 파일을 자동으로 읽으며 변경 후 재시작한다. `.env`는 Git에서 제외한다. 키는 앱·저장소·로그에 넣지 않는다. 기본 모델은 `gpt-4o-mini`이며 `OPENAI_MODEL`로 바꿀 수 있다. OpenAI [Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses), [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)를 사용한다.

AI는 **사용자가 직접 작성한 500자 이하의 요청만** 입력받아 분위기·상황·장르·언어 조건 JSON으로 변환한다. 곡명·URL을 생성하지 않는다. 서버가 등록된 곡에서 선택하며 앱 내부 좋아요를 반영하고 싫어요 곡은 제외한다. 외부 곡 데이터·외부 열기 기록·저장 행동을 AI에 보내지 않는다. `store: false`로 요청한다.

키가 없거나 연결 실패·잘못된 응답·한도 초과가 발생하면 **기본 추천**으로 이어가고 화면에서 알린다. AI 호출은 사용자당 1시간 20회, 서버 전체 UTC 하루 200회를 기본 상한으로 삼으며 시도도 횟수에 포함한다. `AI_USER_HOURLY_LIMIT`, `AI_DAILY_LIMIT`로 조정한다. 실제 과금 상한은 OpenAI 프로젝트의 예산·권한 설정도 적용한다.

2026-10-03 실제 OpenAI 호출로 자연어 조건 해석과 등록된 곡 8개 반환을 확인했다. 로컬 응답 서버 검사는 실제 HTTP 요청 형식·출력 파싱·한도·기본 추천 복구를 별도로 확인한다.

## 저장·인증

서버는 앱 설치별 익명 세션을 발급한다. 32바이트 난수 토큰은 기기의 비공개 설정에, SHA-256 해시만 DB에 보관한다. 사용자 입력으로 소유자를 지정하지 않으며 모든 목록 접근을 토큰 소유자로 확인한다. 소셜 로그인·계정 복구·기기 간 계정 공유는 현재 범위에 포함하지 않는다. 앱을 삭제하면 세션 연결 정보도 삭제된다.

설정·토큰·기기 DB는 클라우드 백업과 기기 간 이전에서 제외한다. Android 12 이상은 일부 기기에서 `allowBackup=false`만으로 기기 이전이 제한되지 않아 [백업 제외 규칙](https://developer.android.com/identity/data/autobackup)을 함께 적용한다.

이름 생성·변경에 실패하면 입력한 이름을 유지하고 해당 입력창에 오류를 표시한다. 처리 중에는 제출 버튼을 잠시 비활성화하고 실패하면 같은 창에서 다시 시도할 수 있다. 곡 검색에서 동작을 선택하면 검색창을 닫아 저장 완료·실패 안내가 뒤에 가려지지 않게 처리한다.

같은 목록에 같은 곡을 재시도해도 한 번만 저장한다. 목록 생성·조회·상세·이름 변경·목록 삭제·곡 제거·곡 순서 변경을 지원한다. 곡 메뉴의 위로·아래로 이동으로 순서를 저장한다. 삭제 전 목록 이름과 곡 수를 확인한다. 내 목록 이름 검색과 검증된 카탈로그의 곡명·아티스트 검색을 지원한다. 검색·순서 변경·저장은 추천 반응으로 기록하지 않는다. 음원 파일은 저장하지 않는다. 외부 목록 이전·긴 영상 변환·랭킹은 아직 구현하지 않았다.

관리 API: `PUT /playlists/{id}`(title), `DELETE /playlists/{id}`, `PUT /playlists/{id}/items/order`(itemIds). 순서 요청은 해당 목록의 모든 항목 ID를 정확히 한 번씩 지정하며 트랜잭션으로 저장한다. `GET /playlists?q=...`, `GET /catalogue?q=...`는 100자 이하 검색어를 받는다. 검색은 대소문자·유니코드 호환 문자 차이를 정규화하고 모든 검색 단어를 확인한다.

목록에 추가할 때 곡 정보와 출처를 함께 보관하여 카탈로그가 변경되어도 저장한 곡 정보를 유지한다. 목록 상세와 스크롤 위치는 화면 재생성 후 복원한다. 서버의 목록 제목·곡은 한 번에 조회하여 동시 삭제 중 빈 목록으로 잘못 표시하지 않는다. 삭제 후 응답이 끊겨 다시 시도한 경우 이미 삭제된 목록은 목록 화면으로 복구한다. 연결 모드를 바꾼 뒤 이전 실패의 재시도로 다른 저장소에 쓰지 않는다.

상세의 ‘목록 텍스트 공유’는 순서대로 곡명·아티스트·검증된 YouTube Music 링크(없으면 출처 링크)를 Android 공유 화면에 전달한다. 사용자가 공유 대상을 고른다. 공개 공유 URL이나 외부 플랫폼의 재생목록 생성 기능은 아니다.

추천 결과의 ‘추천 N곡 모두 저장’으로 현재 추천을 기존 목록 또는 새 목록에 한 번에 담을 수 있다. `POST /playlists/{id}/items/batch`는 `trackIds`로 서로 다른 1~10곡을 받는다. 기기와 서버 모두 일괄 저장을 트랜잭션으로 처리하므로 일부 곡만 저장되는 실패를 방지한다. 같은 추천을 다시 저장해도 목록의 곡이 중복되지 않는다.

PostgreSQL을 사용할 때 `DATABASE_URL`을 JDBC URL로, `DATABASE_USER`, `DATABASE_PASSWORD`를 환경에 설정한다. 동일한 SQL 스키마를 사용한다. H2와 실제 PostgreSQL 15에서 저장·소유권·중복 방지·목록 순서·동시 조회/삭제·서버 재시작을 확인했다. 단일 서버 프로토타입으로 쓰기를 직렬 처리하며 여러 서버로 확장할 때 DB upsert·호출 한도 동시성 검토가 필요하다. 호출 한도는 기존 ISO 시간 기록을 `Instant`로 비교하여 UTC 자정 직후와 서로 다른 소수점 정밀도에서도 정확히 계산한다.

## 추천 화면·수정

프로젝트 세션의 `output/pdf/TapTap_모바일_UIUX_레퍼런스.pdf` 2~3쪽 시안을 참고한다. 입력과 결과를 구분하고, 한 곡에 집중하는 카드·현재/전체 순번·하단 반응과 탐색 버튼·별도의 재생/외부 열기/저장을 제공한다. 코럴 강조색과 슬레이트 본문, 48dp 터치 영역, 줄바꿈·스크롤을 적용한다. 720×1280 화면과 글씨 배율 1.3에서 카드의 세로 스크롤로 재생·외부 열기·저장에 접근하고 하단 반응·탐색 버튼도 화면에서 사용할 수 있음을 확인했다. 하단 반응·탐색 바는 추천 스크롤 바깥에 고정한다. 더 작은 공간이나 더 큰 글씨에서는 카드 바깥의 추천 화면도 스크롤해 요청 수정·전체 저장에 접근할 수 있다. 되돌리기·재시도·저장 안내는 보이는 반응 바 또는 하단 메뉴 위에 표시해 시스템 버튼에 가려지지 않게 처리한다. 시안의 미정 후보 메뉴는 추가하지 않는다.

결과에 실제 해석된 조건을 표시한다. ‘요청 수정’으로 직접 요청을 고친 뒤 다시 추천받을 수 있다. 카드의 ‘곡 수정’에서 같은 조건의 다른 곡으로 바꾸거나 이번 결과에서만 제외하고 되돌릴 수 있다. 현재/이미 제외한 곡은 교체 후보에서 빼고 기존 좋아요·싫어요를 유지한다. 교체는 검증된 카탈로그 안에서 처리하며 추가 AI 호출은 하지 않는다. 임시 제외는 영구적인 싫어요로 저장하지 않는다. 조건·수정 결과와 아직 제출하지 않은 요청 초안은 화면 재생성 후 복원한다. 화면을 다시 만들 때 진행 중인 추천을 유지하여 같은 AI 요청을 다시 보내지 않으며, 새 추천은 첫 곡부터 표시한다. 운영체제가 앱 프로세스를 종료한 경우 자동 재전송하지 않고 직접 다시 시도하도록 안내한다. 화면을 바꾸면 입력 키보드를 닫고, 순번과 플레이어 상태 변경을 화면 읽기에 전달한다.

## 곡 데이터·재생

`catalog/tracks.json`: MusicBrainz 공식 API에서 조회한 실제 녹음 ID·곡명·아티스트·원본 링크. [핵심 메타데이터 CC0](https://musicbrainz.org/doc/About/Data_License)만 사용하고 분위기·상황 태그는 TapTap 샘플로 직접 작성했다. 사용자 태그·평점·앨범 표지·가사·음원은 가져오지 않는다. 샘플 태그는 추천 품질 평가가 완료된 객관적 속성으로 간주하지 않는다.

검증한 재생 링크가 있는 곡은 [공식 YouTube IFrame 플레이어](https://developers.google.com/youtube/iframe_api_reference)를 표시한다. 사용자가 플레이어의 재생을 눌러야 시작한다. 화면 이동·외부 앱 이동·백그라운드 진입 시 플레이어를 종료한다. 앱 ID를 `loadDataWithBaseURL` 출처로 사용해 [공식 클라이언트 식별 요구](https://developers.google.com/youtube/terms/required-minimum-functionality)를 적용한다. 곡의 공식 영상 링크가 없으면 검색으로 연결한다. 외부 Spotify 검색과 YouTube Music 영상·검색 연결을 구분한다. WebView 초기화 실패나 지역·임베드 제한에 따른 재생 실패는 안내와 외부 열기로 복구한다.

카탈로그 재검증: `python3 tools/verify_catalog.py`. 이 작업은 명시적으로 실행할 때만 네트워크를 사용하며 MusicBrainz 요청 간격을 1초 이상 둔다. 동명 코멘터리·영어 버전·Live·Remix·Cover를 제외한다. YouTube oEmbed는 직접 고른 재생 링크의 존재 확인에만 사용하고 그 메타데이터를 추천 카탈로그에 등록하지 않는다.

## 검사

플레이어 준비 지연·상태 표시 검사는 `node tools/check_player.cjs`로 실행한다. 실제 영상 재생과는 구분되는 스크립트 동작 검사다.

```sh
cd backend
../android/gradlew test bootJar
python3 ../tools/check_server.py build/libs/taptap-backend.jar

cd ../android
./gradlew :app:lintDebug :app:connectedDebugAndroidTest --no-configuration-cache
```

`check_server.py`는 실제 JAR을 별도 임시 폴더의 파일 H2로 실행한다. 첫 JVM에서 세션·반응·목록·순서를 저장하고 완전히 종료한 뒤 새 JVM에서 같은 내용과 인증·중복 방지를 확인한다. 검사 데이터는 종료 시 삭제하며 실제 `.env`와 AI 키를 사용하지 않는다.

PostgreSQL 검사는 **빈 전용 검사 DB**를 준비한 뒤 `backend/`에서 실행한다. 운영 DB 환경과 별도의 변수 이름을 사용하며, 같은 검사를 다시 실행할 때 검사 DB도 다시 비워야 한다.

```sh
TAPTAP_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/taptap_check \
TAPTAP_TEST_DATABASE_USER=taptap_check \
TAPTAP_TEST_DATABASE_PASSWORD=your-check-password \
../android/gradlew test --rerun-tasks
```

추천 조건·반응·출처 경계는 `RecommendationCheck`의 실행 가능한 assertion 검사로 확인한다. 서버 통신 검사는 사용자 소유권, 중복 저장, 재시작 보관, OpenAI JSON 계약과 호출 상한, 목록 동시 조회·삭제의 일관성을 확인한다. Android 검사는 별도 테스트 DB에서 중복 저장·반응 변경·취소·재열기 후 보관을 확인하며, 무효가 된 서버 세션으로 재연결하는 경로도 실제 HTTP 응답으로 검사한다. 테스트 DB는 종료 시 삭제한다. 기기 검사는 에뮬레이터가 필요하며 Gradle이 검사 후 대상 앱을 제거할 수 있으므로 시연 전에 디버그 APK를 다시 설치한다.

`AppFlowCheckTest`는 제출 전 요청 초안의 화면 재생성·탭 전환·이전 결과 복귀를 확인하고, 추천·스와이프/버튼 탐색·반응 상태·새 목록 저장·중복 저장·추천 전체 저장·추천 곡 교체·임시 제외·되돌리기·조건 복원·목록 상세 복원·공유 화면 호출·화면 재생성·앱 재열기·출처 확인·곡 제거·이름 변경·순서 변경·목록 삭제·목록/곡 검색·검색에서 저장한 뒤 완료 안내와 저장 목록 재열기를 화면에서 이어 검사한다. 기존 반응과 설정은 검사 후 복구하고 검사에서 만든 목록만 제거한다. `ErrorRecoveryCheckTest`는 지연 응답을 주는 로컬 서버로 진행 중인 추천의 화면 재생성·한 번만 전송·백그라운드 완료 후 첫 곡 표시를 확인한다. 실패한 서버 반응의 재시도가 기본 추천 모드의 기기 반응에 기록되지 않는지도 검사한다. 생성·이름 변경의 서버 실패 응답 뒤 입력과 대화상자를 유지하고 오류를 표시하며, 같은 버튼으로 다시 시도해 갱신된 목록을 확인한다. Android 9 이상에서는 테스트 프로세스의 WebView 초기화를 차단해 플레이어 실패 안내와 외부 선택창도 검사한다. 기기의 WebView 제공자 설정은 변경하지 않는다. 공유 화면 검사는 실제 전송 없이 선택 화면 호출만 가로채 확인한다. Android 7(API 24)에서는 해당하지 않는 WebView 차단 검사 한 개를 제외한 7개, Android 17(API 37)에서는 8개를 실제 에뮬레이터에서 통과했다. 작은 화면의 현재 카드에서 스크롤해 버튼을 찾고 저장 안내가 끝난 뒤 다음 저장을 검사한다. Gradle 화면 검사는 `animationsDisabled`를 적용하며, Android Studio에서 직접 검사할 때도 [공식 Espresso 환경 안내](https://developer.android.com/training/testing/espresso/setup#set-up-environment)에 따라 검사 기기의 애니메이션을 꺼야 한다.

GitHub Actions는 초안 포함 PR과 main 브랜치의 변경에서 서버·추천 검사와 Android 디버그·릴리스 빌드·lint를 실행한다. 서버 검사는 Java 21에서 H2·[별도 PostgreSQL 서비스](https://docs.github.com/en/actions/tutorials/use-containerized-services/create-postgresql-service-containers)와 실제 JAR의 파일 DB 보관을 확인하고, Android 빌드는 프로젝트 데몬 설정에 맞춘 Java 25를 사용한다. AI 키와 실제 외부 AI 호출은 필요하지 않다. 에뮬레이터 화면 검사는 위 명령으로 별도 실행한다.

공유 가능한 APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
실행 가능한 서버: `backend/build/libs/taptap-backend.jar`.
