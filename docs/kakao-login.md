# 카카오 로그인 구현과 설정

카카오톡 로그인은 앱 계정 인증이다. Spotify·YouTube Music 계정 연결은 음악 서비스의 별도 OAuth 승인이다. 카카오 로그인을 해도 음악 서비스 권한을 자동으로 얻지 않는다.

## 동작

1. 앱 아이콘을 누르면 카카오 로그인 첫 화면이 열린다. 노란색 **카카오 로그인** 버튼으로 인증하거나, 바로 아래 **게스트로 시작하기**로 추천 화면에 진입한다. 계정 탭에서도 같은 카카오 로그인을 사용할 수 있다. 최초 서버 사용 시 기존 이용 안내·동의를 확인한다.
2. 앱 키와 서버 앱 ID 설정을 확인한다. 설정이 없으면 같은 창에서 안내하고 기존 세션은 유지한다.
3. 카카오톡이 사용 가능하면 공식 Android SDK로 인증한다. 설치되지 않았거나 카카오톡 인증이 실패하면 브라우저의 카카오계정 로그인을 사용한다. 사용자가 취소한 경우에는 다시 띄우지 않는다.
4. SDK의 액세스 토큰을 TapTap 서버로 한 번 전달한다. 서버는 카카오 공식 `/v1/user/access_token_info`와 `/v2/user/me`로 토큰 만료·앱 ID·동일 사용자 ID를 확인한다.
5. 서버가 별도의 32바이트 난수 TapTap 토큰을 발급한다. 기존 익명 사용자는 같은 소유자로 가입하여 목록·반응을 유지한다. 이미 가입한 카카오 사용자는 자신의 기존 서버 데이터를 불러온다. 이메일 일치로 다른 계정을 합치지 않는다. 첫 로그인 응답이 끊겨 이전 익명 토큰이 무효가 되어도, 동일한 카카오 계정의 유효한 인증으로 기존 소유자와 데이터를 복원한다. 새로운 카카오 계정에는 무효한 이전 소유권을 적용하지 않는다.
6. 첫 화면에서 로그인에 성공하면 추천 화면으로 이동한다. 취소·실패 시 첫 화면을 유지해 다시 로그인하거나 게스트로 시작할 수 있다. 성공·실패·취소는 회전한 새 화면에서도 한 번 반영한다. 서버·소유자가 변경된 뒤 도착한 이전 콜백은 적용하지 않는다.

닉네임·이메일은 선택 프로필이다. 이메일 권한이 없거나 유효/인증되지 않은 이메일이어도 로그인할 수 있다. 소유권은 카카오 사용자 ID로 결정한다.

## 카카오 개발자 설정

[카카오 Developers](https://developers.kakao.com/)에서 Android 앱을 만든다. [Android 시작하기](https://developers.kakao.com/docs/ko/android/getting-started), [Android 로그인](https://developers.kakao.com/docs/ko/kakaologin/android)을 기준으로 설정한다.

- 카카오 로그인 활성화.
- Android 패키지 이름: `com.mptaptap.playlisthub`.
- 실제 빌드 서명 인증서의 키 해시를 등록한다. 디버그·제출용 서명은 다르므로 각각 등록한다.
- 네이티브 앱 키를 Android 빌드에 넣는다. 숫자 앱 ID를 서버에 넣는다. 두 값은 같은 카카오 개발자 앱에서 가져와야 한다.
- 닉네임 등 필요한 동의 항목을 설정한다. 이메일은 필수 항목으로 요구하지 않는다.
- SDK용 Custom Scheme 리디렉션은 앱 매니페스트의 `kakao<네이티브 앱 키>://oauth`와 연결된다. 음악 서비스용 웹 콜백 URI와 구분한다.

디버그 키 해시는 다음 명령으로 확인할 수 있다. 운영용 키는 운영 서명 인증서로 같은 절차를 수행한다.

```sh
keytool -exportcert -alias androiddebugkey \
  -keystore "$HOME/.android/debug.keystore" \
  -storepass android -keypass android | openssl sha1 -binary | openssl base64
```

## 로컬 값 입력

이미 사용하는 `android/local.properties`에 다음 항목을 추가한다. 파일은 Git에서 제외한다.

```properties
kakao.nativeAppKey=카카오에서_발급한_32자리_네이티브_앱_키
```

대신 `KAKAO_NATIVE_APP_KEY` 환경 변수를 설정해 빌드해도 된다. Android 키는 SDK가 사용하는 앱 식별 값이며 APK에 포함된다. REST 키·관리자 키·클라이언트 시크릿을 넣지 않는다. 실제 키를 소스에 적지 않는다. 누락된 빌드는 설치 가능하며 로그인에서 설정 안내를 표시한다. 잘못된 형식은 키 값을 출력하지 않고 빌드를 중단한다.

`backend/.env`에 다음 항목을 추가한다.

```dotenv
KAKAO_APP_ID=카카오_개발자_앱의_숫자_ID
```

서버는 비밀 키 대신 카카오 토큰의 `app_id`를 확인한다. REST API Key·Admin Key·Client Secret이 필요하지 않다. 운영 서버는 HTTPS를 사용한다.

Android 키 변경 후 앱을 다시 빌드·설치하고, 서버 ID 변경 후 서버를 재시작한다.

```sh
cd android
./gradlew :app:assembleDebug

cd ../backend
../android/gradlew test bootJar
java -jar build/libs/taptap-backend.jar
```

## 로그아웃·탈퇴

로그아웃은 현재 기기의 TapTap 세션을 폐기하고 공식 SDK의 카카오 토큰을 정리한다. 다른 기기의 TapTap 세션과 카카오톡 앱 자체의 로그인 상태는 유지한다. 계정 삭제는 브라우저 로그인 화면에서 동일 카카오 계정을 다시 확인한 뒤 서버의 계정·연결·목록 등 소유 데이터를 삭제한다. 다른 카카오 계정의 증명으로는 삭제할 수 없다. 실패하면 기존 TapTap 세션과 데이터가 유지된다. 기존 이메일 계정의 비밀번호 확인 삭제도 유지한다.

카카오 SDK 토큰을 서버 DB·앱 자체 설정·화면 저장 상태·재시도 본문·로그에 보관하지 않는다. SDK 내부 토큰 저장은 공식 SDK가 관리한다. TapTap 서버 토큰은 기기의 비공개 설정에, SHA-256 해시는 서버 DB에 보관한다.

## 코드와 검증

- Android: `TapTapApplication`, `HubActivity`, `HubRequest`, `ApiClient`와 SDK 2.25.0, 매니페스트 콜백.
- 서버: `AccountApi`, `KakaoClient`, `Store`, `accounts`·`social_accounts`·`account_sessions`.
- 서버 검사: `KakaoCheckTest`의 설정 누락·토큰 검증·소유권·이메일 분리·마이그레이션·로그아웃·탈퇴·호출 제한.
- Android 검사: `KakaoLoginCheckTest`의 설정 누락·취소·대체 로그인 이벤트·회전 중 단일 교환·소유자 변경·토큰 거절·삭제 증명.

실제 앱 키와 카카오 개발자 설정이 제공되지 않은 현재 빌드에서는 실계정 카카오톡/브라우저 인증을 실행하지 않았다. 모의 콜백·공식 응답 계약 검사는 실서비스 동의·서명 등록의 성공을 대신하지 않는다. 최신 실행 결과는 [검증 기록](tests-and-evidence.md)을 따른다.
