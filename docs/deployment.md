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

Spotify와 Google 개발자 콘솔에는 각각 아래 콜백을 등록하고 기존 예제의 client ID/secret에 발급받은 값을 넣는다. Apple Music은 `APPLE_MUSIC_DEVELOPER_TOKEN`과 storefront를 설정한다. 키가 없는 서비스는 앱에서 설정 필요 상태로 표시된다.

```text
https://실제도메인/connections/spotify/callback
https://실제도메인/connections/youtube/callback
https://실제도메인/connections/apple_music/callback
```

## 설정 확인과 실행

비공개 환경 파일을 Compose의 변수 치환과 앱 실행에 함께 사용한다. `DOMAIN`과 DB 비밀번호가 비어 있으면 설정 검사가 실패한다. `config --quiet`는 키 값을 출력하지 않고 설정 형식만 검사한다. [Compose 환경 파일](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

```sh
docker compose --env-file backend/.env -f deploy/compose.yaml config --quiet
docker compose --env-file backend/.env -f deploy/compose.yaml up -d --build
docker compose --env-file backend/.env -f deploy/compose.yaml ps
```

이미지는 기존 Gradle wrapper로 `backend`, `core`, `catalog`를 빌드하며 실행 단계에는 JAR과 Java 21 JRE만 들어간다. 서버는 UID 10001로 실행한다. Dockerfile 전용 ignore 파일은 비공개 환경 파일·DB·앱 빌드 결과·Git을 빌드 전송에서 제외한다. [Docker 빌드 단계](https://docs.docker.com/build/building/multi-stage/), [빌드 입력 제외](https://docs.docker.com/build/concepts/context/#dockerignore-files), [Temurin 공식 이미지](https://hub.docker.com/_/eclipse-temurin)

앱과 DB는 호스트 포트를 공개하지 않고 Caddy만 80·443을 사용한다. DB 준비 확인 후 서버를 시작한다. 앱의 기본 게이트웨이는 외부 통신용 네트워크로 지정해 OpenAI와 음악 서비스에 연결하고, DB는 내부 네트워크에서만 접근한다. [Compose 게이트웨이 설정](https://docs.docker.com/reference/compose-file/services/#gw_priority) 서버가 준비되면 실제 HTTPS 주소로 확인한다.

```sh
curl --fail https://실제도메인/health
```

이 확인이 성공하면 Android 앱의 서버 설정에 같은 `https://실제도메인`을 입력한다. 계정·목록 저장과 다시 불러오기, 플랫폼 연결 콜백, 공개 목록의 실제 공유 링크까지 확인해야 해당 기능의 공개 배포를 확인한 것이다. 컨테이너 실행 성공은 실제 OAuth 승인 성공을 대신하지 않는다.

## 데이터 보관

PostgreSQL 데이터와 Caddy 인증서는 이름 있는 볼륨에 보관된다. 동일한 Compose 프로젝트 `taptap`을 유지하며 재시작한다. 이 구성의 첫 실행은 새 PostgreSQL을 만들고, 로컬 H2 데이터를 자동으로 옮기지 않는다. 기존 운영 DB를 바꿀 때는 백업과 데이터 이전을 먼저 완료한다.

```sh
umask 077
docker compose --env-file backend/.env -f deploy/compose.yaml exec -T database sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > taptap-backup.sql
```

DB 백업과 비공개 `backend/.env`의 암호화 키를 함께 안전하게 보관한다. 중지는 `docker compose --env-file backend/.env -f deploy/compose.yaml down`으로 한다. `down -v`나 볼륨 삭제는 저장된 계정·목록과 인증서도 삭제한다. Caddy의 `/data`는 인증서 보관 폴더이므로 캐시처럼 지우지 않는다. [Caddy 이미지의 데이터 보관](https://hub.docker.com/_/caddy)
