# 전체 기능 구현 상태

2026-10-04 기준. 최신 노션 명세와 사용자의 전체 기능 구현 지시에 따라 F01–F09 및 후순위 기능의 코드를 구현했다. 실제 플랫폼 계정 연결·공개 배포·휴대폰 검증은 아직 완료되지 않았다. 따라서 모든 기능을 실제 서비스에서 사용할 수 있는 상태로 판정하지 않는다.

| 기능 | 구현한 동작 | 확인한 범위 / 남은 검증 |
| --- | --- | --- |
| F01 자연어 추천 | OpenAI 조건 해석, 허용 카탈로그 추천, 부족 결과 안내, 기본 추천 복구 | 실제 OpenAI 최초·추가 요청 통과. 등록된 40곡만 사용 |
| F02 반응 개인화 | 앱 좋아요·싫어요 저장·변경·취소 | 외부 목록·저장·열기 행동의 AI 격리, 사용자별 반응 검사 통과 |
| F03 카드 탐색 | 좌우 스와이프, 이전·다음, 현재 순번, 평가·저장 복원 | API24/37 화면 검사 통과 |
| F04 음악 확인 | 공식 YouTube 플레이어, 시작 시각, 이동·배경 정지, 실패 안내 | 플레이어 스크립트·WebView 실패 복구 검사 통과. 실제 휴대폰/지역별 재생은 별도 |
| F05 외부 열기 | 검증 링크 직접 열기와 곡 검색 구분, 웹 대체 | URL·앱 선택창 검사 통과. 실제 외부 앱별 동작은 별도 |
| F06 내 목록 | 생성·일괄 저장·수정·삭제·제거·순서·검색·텍스트 공유 | 소유권·중복 방지·재시도·회전·재시작 검사 통과 |
| F07 긴 영상 변환 | 공식 설명 조회 또는 사용자 시간표, 검토·수정·제외·원본 위치·저장 | 파서·공식 API 응답 계약·앱 흐름 통과. 실제 Google 설정의 조회 검증 대기 |
| F08 플랫폼 이전 | 연결·가져오기·명시적 확인·비공개 목록 생성·부분 실패 재시도 | 모의 공식 HTTP 계약·응답 유실·중복 방지 통과. Spotify/Google/Apple 실계정 대기 |
| F09 매칭 검토 | ID·ISRC·제목·아티스트·버전·길이, 후보 선택·제외 | 규칙·공식 검색 응답 계약 검사 통과. 실서비스 검색 품질 평가 대기 |
| 대화형 조정 | 이전 조건 유지·교체·초기화, 곡 유지·임시 제외, 초안 복원 | 실제 OpenAI와 API24/37 통과. 명시한 팝·언어·제외 조건 누락 방지 |
| 계정/동기화 | 이메일 가입·로그인·기기별 세션·삭제, 익명 데이터 이어받기 | 암호·다중 세션·계정 격리·앱 로그인 검사 통과. 실제 휴대폰 간 사용 대기 |
| 공개 목록 | 선택적 공개, 읽기 전용 링크, 검색·순위·즐겨찾기·복사 | 권한·HTML·링크 철회·앱 흐름 통과. 공개 HTTPS 주소 검증 대기 |
| Apple Music | MusicKit 허용 페이지·공식 목록 읽기·검색·이전 | 응답 계약·점 포함 목록 ID 검사 통과. 개발자 토큰·실제 MusicKit 계정 대기 |
| 외부 변경 반영 | 실제 저장 곡과 비교, 미리 보기 후 확인 적용 | 수정·순서·삭제 후 오래된 미리 보기 거절, 다른 원본 보존 통과. 실계정 대기 |
| 기록 관리 | 변환·가져오기·전송 재열기, 검토 초안 복원, 기록만 삭제 | 소유권·전송 중 삭제 거절·앱 흐름 통과. 삭제된 앱 목록 재저장·재시도 통과 |
| 보관 정책 | 원본 조회 후 30일 만료, 연결 해제·계정 삭제 | 수동 수정으로 보관 기간 연장 방지, 교차 플랫폼 후보·공개 스냅샷 제거 통과 |
| 배포 | 서버 JAR, Android APK, PostgreSQL·Caddy HTTPS 실행 설정 | Android 빌드·lint, 최종 이미지 실행·PostgreSQL 재시작 보존, Compose 설정·누락 거절 통과. 실제 도메인·배포·휴대폰 대기 |

## 이번 빌드의 검사 근거

- Android 화면 검사: API24 기본·대화 13개 + Hub 1개, API37 기본·대화 15개 + Hub 1개, 합계 30개 통과. [API24](../output/checks/baseline-full/android-api24-tests.json), [API37](../output/checks/baseline-full/android-api37-tests.json), [Hub API24](../output/checks/hub-full/android-api24-tests.json), [Hub API37](../output/checks/hub-full/android-api37-tests.json).
- 서버: [H2 9개](../output/checks/backend-h2-full.json), [PostgreSQL 15 9개](../output/checks/backend-postgresql-full.json), [최종 JAR 재시작](../output/checks/packaged-server-full.json) 통과. PostgreSQL 실행 중 레거시 H2 이전·AI HTTP stub 검사는 H2를 사용한다.
- 배포 패키지: [최종 Docker 이미지 실행](../output/checks/container-full.json)에서 읽기 전용 파일시스템·UID 10001·PostgreSQL 15·서버 재시작 후 계정/목록/공개 스냅샷을 확인했다. [Compose 설정 검사](../output/checks/compose-full.json)도 통과했다. 공개 TLS·실제 도메인 검증은 포함하지 않는다.
- 실제 OpenAI: [최종 호출 근거](../output/checks/openai-full.json). 최초 6곡은 조건에 맞는 카탈로그 부족 안내, 추가 요청 7곡은 유지 곡 포함. 두 응답 모두 OpenAI 모드, 허용 곡만 반환, 반응 데이터 변경 없음. 검사 계정 삭제 완료.
- APK와 서버의 정확한 파일 해시는 [체크섬](../output/checksums.json)에 기록한다. [APK](../output/apk/TapTap-debug.apk), [서버](../output/server/taptap-backend.jar). 검사·패키지는 로컬 산출물이며 Git에 올리지 않는다.

## 실제 연결에 필요한 항목

OpenAI 키는 실제 호출까지 확인했다. Spotify 개발자 앱, Google OAuth 앱/YouTube API 설정, Apple Music 개발자 토큰은 별도다. 비공개 [환경 설정 예제](../backend/.env.example)의 해당 항목과 공개 서버 도메인을 설정한 뒤 실제 허용·가져오기·후보 검토·전송·재시도·변경 반영을 확인해야 한다. 키는 채팅이나 저장소에 넣지 않는다.

공개 HTTPS 실행·백업 절차는 [배포 안내](deployment.md)에 있다. 도메인·호스팅·플랫폼 계정·실제 Android 휴대폰을 아직 제공받거나 연결하지 않았으며 이 검증을 통과한 것으로 기록하지 않는다.
