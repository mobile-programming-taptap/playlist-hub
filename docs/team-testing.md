# 팀 원격 테스트

맥에서 서버를 실행하고 Tailscale로 HTTPS 주소를 제공한다. 기본은 **Funnel(공개)** 방식이며, 팀원 휴대폰에는 Tailscale이 필요 없다. 필요하면 같은 주소를 **Serve(비공개)** 방식으로 되돌려 tailnet 기기에서만 접속하게 할 수 있다. 어느 방식이든 주소가 같으므로 APK·`backend/.env`·OAuth 콜백을 바꿀 필요가 없다. 맥이 잠자기 또는 종료 상태이거나 서버가 꺼지면 접속할 수 없다.

## Funnel로 공개 (기본)

```sh
# 1) backend/에서 서버 실행 (SERVER_ADDRESS는 127.0.0.1 유지)
# 2) 공개 시작: 기존 Serve를 끄고 같은 443 포트로 Funnel을 켠다
tools/funnel.sh on
# 상태 확인 / 공개 해제 / 비공개 Serve로 복귀
tools/funnel.sh status
tools/funnel.sh off
tools/funnel.sh private
```

- 처음 켤 때 CLI가 Funnel 활성화 승인 링크를 보여 준다. 관리 화면에서 승인한 뒤 다시 실행한다.
- 공개 DNS 반영에 최대 10분이 걸릴 수 있다. Tailscale을 끈 휴대폰(LTE)에서 `https://주소/health`로 확인한다.
- 시연 중에는 `caffeinate -dims`로 맥 잠자기를 막는다. 재부팅 후에는 서버를 다시 실행하고 `tools/funnel.sh status`로 확인한다.
- 주소를 아는 누구나 API를 호출할 수 있다. OpenAI 비용과 데이터 노출을 고려해 테스트·시연이 끝나면 `tools/funnel.sh off`로 끈다.
- 팀원은 같은 APK를 설치하고 최초 이용 동의 후 사용한다. Google·Spotify 음악 계정 연결은 각 콘솔의 테스트 사용자만 가능하다.

## Serve로 비공개 운영 (선택)

아래 1~6은 처음 주소를 만들 때의 공통 준비이며, 비공개 운영에서는 팀원도 Tailscale에 참여해야 한다.

### 개발자가 한 번 준비할 것

1. 맥에 공식 Tailscale을 설치·로그인하고 VPN 권한을 허용한다.
2. 기존 TapTap 서버를 `backend/`에서 실행한다. 주소는 `127.0.0.1:8080`으로 유지해 외부 LAN에는 직접 열지 않는다.
3. Tailscale CLI에서 `tailscale serve --bg http://127.0.0.1:8080`을 실행한다. 비공개로 운영할 때는 Funnel을 끈다(`tools/funnel.sh private`). 발급받은 `https://기기명.네트워크명.ts.net` 주소를 사용한다. 기기 도메인 이름은 공개 인증서 기록에 남지만 Serve 접속은 비공개 네트워크 안으로 제한된다.
4. `backend/.env`의 `PUBLIC_BASE_URL`을 그 주소로, `GOOGLE_REDIRECT_URI`와 `SPOTIFY_REDIRECT_URI`를 각각 그 주소 + `/connections/youtube/callback`, `/connections/spotify/callback`으로 지정하고 서버를 재시작한다. 기존 암호화 키와 DB는 유지한다. Google·Spotify 콘솔에도 같은 콜백을 등록해야 음악 계정 연결이 가능하다. Tailscale은 해당 제공자의 사용자 승인이나 테스트 사용자 등록을 대신하지 않는다.
5. `android/local.properties`에 `taptap.apiBase=발급받은HTTPS주소`를 지정하고 APK를 빌드한다. 환경 변수 `TAPTAP_API_BASE` 또는 Gradle 속성 `taptap.apiBase`가 로컬 설정보다 우선한다. 주소를 바꾸면 APK를 다시 배포한다. 개발 에뮬레이터는 기본 주소를 설정하지 않았을 때만 맥의 `10.0.2.2:8080`으로 자동 연결한다.
6. Tailscale 관리 화면에서 팀원의 계정만 초대하거나 맥 서버 기기만 공유한다. 비공개 운영 중에는 무관한 사용자 초대·공개 링크·Funnel을 사용하지 않는다. 더 많은 기기가 있는 네트워크라면 접근 정책을 맥의 TCP 443으로 제한한다. 초대를 만들었다는 것과 팀원의 수락·실제 접속 완료는 구분한다.

### 비공개 운영의 사용자와 팀원

- Android 8 이상 기기에 Tailscale을 설치하고 본인 계정으로 로그인한다. 팀원은 초대를 수락하고 연결을 켠다. 비밀번호를 공유하지 않는다.
- 개발자가 제공한 동일한 TapTap APK를 설치한다. 최초 서비스 이용 안내에 동의하면 서버 주소 입력 없이 연결된다. 동의하지 않으면 기기에 저장한 목록을 계속 사용할 수 있다.
- 재실행 시 같은 서버의 세션을 재사용한다. 일시적인 연결 실패는 앱의 `다시 시도 · 시작하기` 또는 설정의 `연결 다시 시도`로 복구한다. 음악 서비스의 Google·Spotify 로그인과 권한 허용은 별도다.
- 기본 서버가 바뀌어도 기존 서버 토큰·기기 목록은 삭제하거나 새 서버로 복사하지 않는다. 새 서버에서는 필요한 경우 다시 로그인한다. 서버의 기존 익명 데이터는 원래 세션 소유로 남는다.

## 확인과 중지

- 비공개 운영이면 `tailscale serve status`에서 `tailnet only`인지, 공개 운영이면 `tools/funnel.sh status`에서 Funnel이 켜졌는지 확인하고, HTTPS 주소의 `/health`로 응답을 확인한다. 이 응답의 `aiConfigured`는 키의 설정 여부만 뜻하며 유효한 AI 응답을 보장하지 않는다.
- 팀원의 다른 네트워크 기기에서 앱 실행·계정·목록 조회를 확인해야 두 사람의 원격 사용 검수가 끝난다. 비공개 운영이면 VPN을 끈 외부 기기에서 접속되지 않는지, 공개 운영이면 Tailscale 없는 기기에서 접속되는지 확인한다.
- 중지: 공개는 `tools/funnel.sh off`, 비공개는 `tailscale serve --https=443 off`. 사용자를 제외하려면 관리 화면에서 해당 초대·공유·기기를 해제한다.

공식 안내: [Tailscale Funnel](https://tailscale.com/kb/1223/funnel), [Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve), [팀 개발 서버 공유](https://tailscale.com/docs/use-cases/application-testing/share-local-dev-server-with-team), [무료 개인 요금제](https://tailscale.com/pricing).
