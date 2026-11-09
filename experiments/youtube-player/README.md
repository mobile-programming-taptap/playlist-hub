# YouTube 공식 플레이어 실험

프로젝트 루트에서 실행:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory experiments/youtube-player
```

http://localhost:8765 에서 ‘영상 불러오기’ → 플레이어 재생 버튼을 누른다. 다른 곡은 URL 전체가 아니라 11자리 영상 ID를 입력한다. 파일을 직접 여는 file:// 방식 대신 로컬 서버를 사용한다. 서버 종료는 실행한 터미널에서 Ctrl+C.

로직 검사:

```sh
node experiments/youtube-player/check.cjs
```

## 확인 결과 — 2026-09-22

- Codex 내장 브라우저에서 BTS ‘Dynamite’ 공식 MV(gdZLi9oWNZg) 로드 성공.
- 공식 플레이어 재생 이벤트 확인, 화면의 재생 시간 19초까지 진행 확인.
- 일시정지 확인 후 실험 종료. 시스템 스피커의 실제 음량·청취 여부는 검증하지 않음.
- 입력 검증, 플레이어 재사용, 재생·오류 상태 및 원본 링크 로직 검사 통과.
- Android WebView, 실제 휴대폰, 다른 영상·계정·지역에서는 미검증.

공식 영상을 그대로 표시하는 실험이며 오디오 추출·다운로드·백그라운드 재생·30초 클립 생성은 하지 않는다. MVP에 포함하는 결정은 별도다.

문서: https://developers.google.com/youtube/iframe_api_reference
