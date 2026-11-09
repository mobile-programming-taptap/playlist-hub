# 공개 HTTPS 서버 실행

이 구성은 Java 21 서버, PostgreSQL 15, Caddy를 한 호스트에서 실행한다. 실제 도메인·호스팅·플랫폼 개발자 키는 포함하지 않으며, 이 파일을 추가한 것만으로 공개 배포가 완료되지는 않는다.

## 준비

Docker Engine과 Compose 2.33.1 이상이 있는 서버와 실제 도메인이 필요하다. 도메인의 A/AAAA 레코드를 해당 서버로 연결하고 TCP 80·443을 외부에서 접근할 수 있게 설정한다. HTTP/3을 사용할 때는 UDP 443도 연다. Caddy가 도메인으로 인증서를 발급·갱신하고 HTTPS로 전달한다. [Caddy HTTPS 안내](https://caddyserver.com/docs/quick-starts/https)

모든 명령은 저장소 최상위 폴더에서 실행한다. 기존 `backend/.env`가 있으면 그대로 사용한다. 처음 설정하는 서버에서는 기존 예제를 복사한다.

```sh
cp backend/.env.example backend/.env
chmod 600 backend/.env
```

`backend/.env`에 다음 항목을 추가하고 실제 값을 넣는다. `DOMAIN`에는 `https://`나 경로 없이 호스트 이름만 쓴다. 비밀번호 예제 값을 실제 비밀번호로 바꿔야 한다.

```dotenv
DOMAIN=music.your-domain.example
DATABASE_USER=taptap
DATABASE_PASSWORD=replace-with-a-long-random-password
```

기존 항목 `PUBLIC_BASE_URL`, `SPOTIFY_REDIRECT_URI`, `GOOGLE_REDIRECT_URI`도 같은 도메인에 맞춘다. Compose는 앱의 `PUBLIC_BASE_URL`을 `https://DOMAIN`으로 설정한다. `DATABASE_URL`은 내부 PostgreSQL로 설정되므로 로컬 H2 주소를 적을 필요가 없다.

`OPENAI_API_KEY`는 기존 키를 사용한다. `PLATFORM_TOKEN_SECRET`은 32바이트를 Base64로 인코딩한 값이며 플랫폼 연결 정보를 암호화한다. 처음 설정할 때만 아래 명령으로 생성한 값을 입력한다. 기존 연결 정보가 있다면 기존 암호화 키를 유지한다.

```sh
openssl rand -base64 32
```

Spotify와 Google 개발자 콘솔에는 각각 아래 콜백을 등록한다. Spotify는 PKCE를 사용하므로 `SPOTIFY_CLIENT_ID`만 필요하며 Client Secret은 사용하지 않는다. Google은 `GOOGLE_CLIENT_ID`와 `GOOGLE_CLIENT_SECRET`에 발급받은 값을 넣는다. 키가 없는 서비스는 앱에서 설정 필요 상태로 표시된다.

```text
https://실제도메인/connections/spotify/callback
https://실제도메인/connections/youtube/callback
```

카카오 로그인은 같은 개발자 앱의 숫자 `KAKAO_APP_ID`를 이 환경 파일에 추가한다. Compose의 `env_file`이 서버에 전달한다. Android에는 `KAKAO_NATIVE_APP_KEY` 또는 `local.properties`의 `kakao.nativeAppKey`를 넣고 재빌드·재설치한다. Android 패키지·서명 키 해시·동의 설정은 [카카오 설정](kakao-login.md)을 따른다. 음악 서비스 웹 콜백과 카카오 SDK 리디렉션은 별도다.

## 공개 안내 페이지와 플랫폼 설정

서버는 로그인 없이 `https://실제도메인/`에서 앱 소개, `https://실제도메인/privacy`에서 현재 데이터 처리 안내, `https://실제도메인/account-deletion`에서 앱 외부의 계정·데이터 삭제 요청 경로를 제공합니다. 공개 목록에서도 안내에 연결합니다. OpenAI에 보내는 직접 입력·대화 조건, 기기에 보관하는 최근 요청, 외부 메타데이터의 30일 보관, 공개·연결 해제·계정 삭제 범위를 설명합니다.

운영자가 실제 수신하는 문의 주소를 `SUPPORT_EMAIL`에 설정하면 삭제 요청의 수신자로 사용합니다. 삭제 요청 버튼은 사용자의 메일 앱에서 고정 제목·안내 초안을 열며 사용자가 직접 발송해야 합니다. 서버는 메일을 보내거나 웹 요청을 자동 접수하지 않습니다. 값이 없거나 형식이 잘못되면 주소를 만들지 않고 삭제 요청을 접수할 수 없는 상태임을 알립니다. 로컬·격리 검사는 실제 주소 없이 실행할 수 있습니다.

실제 공개 운영을 준비한다면 서버에 적용할 동일한 `SUPPORT_EMAIL`을 실행 환경에 설정한 뒤 아래의 별도 검사를 통과해야 합니다. 주소나 비밀값은 결과에 출력하지 않으며 네트워크·컨테이너·메일 전송도 실행하지 않습니다. 이 검사는 형식과 설정 여부만 확인합니다.

```sh
python3 tools/check_deployment.py --production-contact-check
```

운영자는 메일의 수신·회신을 실제 확인하고, 이름이나 발신 주소만으로 타인의 계정을 삭제하지 않는 본인 확인 절차를 마련해야 합니다. 비밀번호·카카오 인증 코드·인증 토큰을 메일로 요구하지 않습니다. 앱을 다시 설치하지 않고도 요청할 수 있어야 하며 소유권을 확인한 요청은 기존 계정 삭제 범위에 맞춰 처리합니다. Google·YouTube 사용자 데이터의 삭제 요청은 관련 정책의 7일 이내 기준을 준수해야 합니다. 계정 삭제는 본인의 공개 링크도 닫지만 외부 플랫폼 계정·이미 생성한 외부 목록·다른 사용자의 독립 복사본까지 삭제하지는 않습니다. 실제 운영자 정보·요청 처리 일정·로그와 백업의 보관 및 삭제 정책은 공개 운영 전에 확정해 안내에 반영해야 합니다. 이 문서나 주소 설정만으로 운영 정책이 확정되거나 실제 요청이 처리된 것은 아닙니다. [Google Play의 외부 계정 삭제 요청 요건](https://support.google.com/googleplay/android-developer/answer/13327111), [YouTube 데이터 삭제 정책](https://developers.google.com/youtube/terms/developer-policies)

Google OAuth 앱의 홈페이지와 개인정보 안내 주소는 같은 실제 도메인으로 등록하고 소유권을 확인합니다. 공개된 페이지와 안내 링크·처리 설명은 [Google의 민감한 범위 검증 안내](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)의 준비 항목입니다. 이 페이지를 제공하는 것만으로 Google 검증·OAuth 승인이나 공개 배포가 완료되는 것은 아닙니다. 제한된 테스트 사용자 설정과 공개 서비스 검증은 구분합니다.

## 공개 목록 신고 검토

목록 공개 전에는 서버의 현재 이용 규칙(`GET /community/rules`)을 사용자가 확인하고 명시적으로 동의해야 합니다. `PUT /community/rules/acceptance`에 현재 `version`과 `accepted:true`를 전달한 사용자만 공개할 수 있으며, 규칙 버전이 바뀌면 다시 동의해야 합니다. 동의하지 않아도 개인 목록을 사용할 수 있습니다.

앱의 목록·작성자 신고는 DB에 `OPEN` 상태로 저장합니다. 자동 알림·자동 삭제·사람의 검토 완료를 주장하지 않습니다. 신고에는 신고자·대상 내부 식별자, 원래 공개 링크·목록 식별자, 사유, 선택 설명(500자 이내), 상태·시각을 보관하며 AI 추천·개인화에 사용하지 않습니다. 새 신고는 사용자당 24시간 동안 10건으로 제한하고 중복 요청은 기존 신고를 반환합니다. 공개 해제·목록 삭제만으로 미처리 신고를 없애지 않으며, 신고자나 대상 계정 삭제 시 관련 신고는 함께 삭제합니다. 차단과 규칙 동의도 해당 계정 삭제 시 정리합니다.

운영 검토 API를 사용할 환경에서는 비공개 환경 파일에 별도 `MODERATION_ADMIN_SECRET`을 설정합니다. 허용 형식은 영문·숫자·`_`·`-` 32~256자입니다. 실제 값을 앱·문서·쿼리 문자열·공유 URL에 넣지 않습니다. 비어 있거나 형식이 잘못되면 검토 API는 503을 반환하며 신고는 저장된 `OPEN` 상태로 남습니다. 실제 운영에는 담당자와 검토 절차가 별도로 필요하며 이 설정은 공개 운영 승인이나 운영자 배정을 뜻하지 않습니다.

검토 요청은 HTTPS에서 `X-TapTap-Moderation-Key` 헤더로만 인증합니다. 키가 없거나 다르면 401을 반환합니다. `GET /moderation/reports?status=OPEN`은 오래된 신고부터 최대 100건을 반환하며 `DISMISSED`·`HIDDEN`도 조회할 수 있습니다. 응답에는 신고 ID·원래 공유 ID·대상 종류·사유·설명·상태·생성 시각만 포함하고 사용자 이메일·내부 계정 ID는 노출하지 않습니다. `POST /moderation/reports/{reportId}/decision`의 본문은 `{"action":"dismiss"}` 또는 `{"action":"hide"}`입니다. `hide`는 목록 신고면 같은 소유자가 가진 원본 목록을 공개되지 않도록 표시하고, 작성자 신고면 해당 작성자의 현재 공개 목록들만 숨깁니다. 이미 비공개인 원본도 재공개는 거부하며, 이미 다른 사용자가 독립 복사한 목록을 삭제하지 않습니다. 응답의 `hiddenPublications`는 이번에 실제로 숨긴 공개본 수입니다. 같은 결정을 다시 요청하면 중복 처리하지 않고 다른 결정으로 뒤집는 요청은 409를 반환합니다.

개인 작성자 차단은 로그인한 사용자의 앱 검색·순위·즐겨찾기·상세·복사에서 대상을 숨깁니다. 차단 관리에서 해제할 수 있으며, 개인 차단을 비로그인 공개 웹 링크 전체 폐쇄로 표시하지 않습니다.

## 설정 확인과 실행

비공개 환경 파일을 Compose의 변수 치환과 앱 실행에 함께 사용한다. `DOMAIN`과 DB 비밀번호가 비어 있으면 설정 검사가 실패한다. `config --quiet`는 키 값을 출력하지 않고 설정 형식만 검사한다. [Compose 환경 파일](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

```sh
docker compose --env-file backend/.env -f deploy/compose.yaml config --quiet
docker compose --env-file backend/.env -f deploy/compose.yaml up -d --build
docker compose --env-file backend/.env -f deploy/compose.yaml ps
```

이미지는 기존 Gradle wrapper로 `backend`, `core`, `catalog`를 빌드하며 실행 단계에는 JAR과 Java 21 JRE만 들어간다. 서버는 UID 10001로 실행한다. Dockerfile 전용 ignore 파일은 비공개 환경 파일·DB·앱 빌드 결과·Git을 빌드 전송에서 제외한다. [Docker 빌드 단계](https://docs.docker.com/build/building/multi-stage/), [빌드 입력 제외](https://docs.docker.com/build/concepts/context/#dockerignore-files), [Temurin 공식 이미지](https://hub.docker.com/_/eclipse-temurin)

앱과 DB는 호스트 포트를 공개하지 않고 Caddy만 80·443을 사용한다. Caddy가 전달한 실제 접속 주소를 서버가 읽도록 설정해 사용자별 로그인 실패 제한이 프록시 주소 하나로 합산되지 않게 한다. Caddy는 외부 요청이 보낸 전달 헤더를 기본적으로 무시하므로 클라이언트가 주소를 위조해 제한을 우회할 수 없다. 앱 포트를 직접 공개하거나 Caddy 앞에 다른 프록시를 둘 때는 신뢰할 프록시 설정을 다시 확인해야 한다. [Spring 프록시 설정](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server), [Caddy 전달 헤더](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults)

DB 준비 확인 후 서버를 시작한다. 앱의 기본 게이트웨이는 외부 통신용 네트워크로 지정해 OpenAI와 음악 서비스에 연결하고, DB는 내부 네트워크에서만 접근한다. [Compose 게이트웨이 설정](https://docs.docker.com/reference/compose-file/services/#gw_priority) 서버가 준비되면 실제 HTTPS 주소로 확인한다.

```sh
curl --fail https://실제도메인/health
```

이 확인이 성공하면 개발자가 `TAPTAP_API_BASE=https://실제도메인`을 지정하거나 `android/local.properties`에 `taptap.apiBase=https://실제도메인`을 넣고 APK를 빌드한다. 사용자는 서버 주소 입력 없이 최초 이용 동의 후 자동 연결한다. 계정·목록 저장과 다시 불러오기, 플랫폼 연결 콜백, 공개 목록의 실제 공유 링크까지 확인해야 해당 기능의 공개 배포를 확인한 것이다. 컨테이너 실행 성공은 실제 OAuth 승인 성공을 대신하지 않는다.

공개 서버를 만들지 않고도 로컬에서 배포 품질을 검사할 수 있다. 아래 검사는 별도 임시 DB·인증서·프로젝트와 임의의 로컬 포트를 만들고 종료 시 제거하며, 호스트 인증서 신뢰 설정과 실제 키를 사용하지 않는다. Docker의 현재 연결 설정을 그대로 사용하고, HTTPS·재시작 보관·공유·로그인 제한·삭제 요청 페이지를 확인한다. AI 미설정은 고정 추천으로 대체하지 않고 503을 반환해야 한다. PostgreSQL의 `pg_dump` 결과를 원본과 다른 새 DB에 복원하고, 검사 앱만 그 DB로 전환하여 동일 세션·목록·공유를 읽는다. 원래 사용 중인 DB나 서버·기기 데이터에는 접근하지 않는다. 로컬 성공은 공개 도메인과 실제 OAuth 검증을 뜻하지 않는다.

```sh
docker build -f backend/Dockerfile -t taptap-deployment-check:local .
python3 tools/check_deployment.py --jar output/server/taptap-backend.jar
```

`--jar`에는 해당 이미지와 같은 소스로 패키징한 실제 JAR을 지정한다. 파일이 없거나 이미지 내부 JAR의 SHA-256과 다르면 검사가 실패한다. 예전 빌드나 단순 소스 검사 결과를 현재 배포 패키지의 근거로 사용하지 않는다. 임시 DB 덤프·비밀번호·세션은 결과에 출력하지 않으며 결과에는 패키지·백업 해시와 검사 범위만 기록한다. 이 검사는 실제 이메일 발송·운영자 수신·외부 제공자 승인·공개 배포를 실행하지 않는다.

## 데이터 보관

PostgreSQL 데이터와 Caddy 인증서는 이름 있는 볼륨에 보관된다. 동일한 Compose 프로젝트 `taptap`을 유지하며 재시작한다. 이 구성의 첫 실행은 새 PostgreSQL을 만들고, 로컬 H2 데이터를 자동으로 옮기지 않는다. 기존 운영 DB를 바꿀 때는 백업과 데이터 이전을 먼저 완료한다.

```sh
umask 077
docker compose --env-file backend/.env -f deploy/compose.yaml exec -T database sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > taptap-backup.sql
```

DB 백업과 비공개 `backend/.env`의 암호화 키를 함께 안전하게 보관한다. 중지는 `docker compose --env-file backend/.env -f deploy/compose.yaml down`으로 한다. `down -v`나 볼륨 삭제는 저장된 계정·목록과 인증서도 삭제한다. Caddy의 `/data`는 인증서 보관 폴더이므로 캐시처럼 지우지 않는다. [Caddy 이미지의 데이터 보관](https://hub.docker.com/_/caddy)
