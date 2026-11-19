# 내 플레이리스트 내보내기 전용 흐름 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 라이브러리 화면에서 목록 하나를 골라 다른 플랫폼으로 보낸다. 흐름은 가져오기 → 자동 매칭 → 확인 필요한 곡만 검수 → 전송이다.

**Architecture:**
- 검수 그룹 분류, 자동 선택, 버튼 문구, 결과 줄은 화면과 무관한 순수 Java 클래스 `ExportReview`로 분리한다. JVM 단위 테스트로 고정한다.
- 화면과 네트워크 순서는 기존 `HubActivity`의 `screen` 상태 머신에 `export` 화면과 `export*` 라우트를 추가해서 구현한다.
- 백엔드는 바꾸지 않는다.

**Tech Stack:**
- 앱: Java, Android Views, Material Components, `org.json`
- 테스트: JUnit4(JVM), Espresso 계측 테스트(루프백 가짜 서버 패턴)

**Spec:** `docs/superpowers/specs/2026-11-18-export-flow-design.md`

## 실행 전 준비

`CLAUDE_HANDOFF.md` 1항에 따라 진행한다. 이 로컬 폴더의 Git에는 커밋이 없다.

**기준 코드 (2026-11-18 확인)**
- `origin/main`은 `13e83b9`다. PR #6~#9가 병합된 상태다(행 목록 UI, welcome 수정, 발견 탭 제거).
- 열린 PR #10 `feat/ui-row-layout`(팀원 작성, CI 통과, 병합 가능)도 `HubActivity.java`와 `HubFlowCheckTest.java`를 수정한다. 변경 내용은 곡 확인 화면의 행 목록·체크박스, 대상 플랫폼 버튼 토글이다.
- 충돌을 줄이기 위해 **PR #10 head 위에 브랜치를 쌓는다.** 이 계획이 참조하는 메서드는 PR #10 코드에 모두 그대로 있음을 확인했다:
  - `library`, `startLibraryImport`, `review`, `select`, `chooseCandidate`, `approveTransfer`, `transfer`, `failure`, `providerPicker`·`selectProvider`, `SAVED_TEXT`, `libraryConnect` 분기
- PR #10이 병합되기 전에 이 브랜치의 PR을 올리면, 대상 브랜치를 `feat/ui-row-layout`으로 두거나 PR #10 병합 후 `main`으로 rebase한다.

**worktree**
- 정상 클론은 `~/playlist-hub-upstream`이다.
- `git fetch origin pull/10/head:pr10` 후 `git worktree add -b feat/export-flow ../playlist-hub-export pr10`으로 만든다.
- 스펙과 이 계획 파일을 worktree의 같은 경로로 복사해서 첫 커밋에 포함한다.
- `src/test`(JVM 테스트) 폴더는 아직 없다. Task 1에서 처음 만든다.

아래 경로는 worktree 기준이다. 코드 위치는 줄 번호 대신 **메서드 이름**으로 적었다. `origin/main`과 로컬 사본의 줄 번호가 다를 수 있기 때문이다.

## Global Constraints

- 백엔드(`backend/`)는 변경하지 않는다. 사용하는 API:
  - `POST /imports/playlists`
  - `POST /matches` (offset 페이지)
  - `GET /matches/{id}?targetProvider=`
  - `PUT /matches/{id}`
  - `POST /transfers`, `GET /transfers/{id}`
- '확인 필요' 곡(`NEEDS_REVIEW`, 후보가 있는 `UNKNOWN`)은 사용자가 직접 고르기 전에는 전송되지 않는다. (명세 F-09)
- 매칭이 끝나지 않았거나(`nextOffset >= 0`) 확인 필요 곡이 남아 있으면 보내기 버튼은 비활성이다.
- 내보내기는 한 번에 목록 1개만 보낸다.
- 매칭 자동 이어 찾기는 내보내기 흐름에서만 동작한다. 기존 `review()` 화면은 수동으로 유지한다.
- 상태 복원 범위는 회전과 프로세스 종료다(기존 Bundle 경로). 복원 뒤에 자동 매칭을 다시 시작하지 않는다.
- 지원 플랫폼은 `PROVIDERS={"spotify","youtube"}` 두 개다. 대상은 출발 플랫폼이 아닌 쪽 하나로 정해지므로 대상 선택 대화상자를 띄우지 않는다. (스펙 6절의 대화상자는 대상이 2개 이상일 때만 의미가 있다.)
- 문구(확정 카피):
  - `다른 플랫폼으로 보내기`
  - `선택한 목록 앱에 저장`
  - `보내기는 한 번에 한 목록씩 할 수 있어요.`
  - `{N}곡 중 {M}곡 찾는 중`
  - `중단`
  - `이어서 찾기`
  - `확인이 필요한 곡 ({n})`
  - `찾지 못한 곡 ({n}) · 제외됨`
  - `선택된 곡 ({n})`
  - `이 곡으로`
  - `다른 후보`
  - `제외`
  - `확인 필요 {n}곡 남음`
  - `곡 후보 찾는 중`
  - `Spotify로 {n}곡 보내기`, `YouTube Music으로 {n}곡 보내기` (조사는 플랫폼별로 고정한다)
  - `자동 선택 {n}곡 포함`
  - `앱의 내 목록에도 사본이 저장됐어요.`

## Review Focus

1. **매칭 중 화면 회전:** 진행 중이던 응답이 도착해도 화면이 `export`로 유지된다. 복원 뒤에는 `이어서 찾기`가 보이고 자동으로 다시 돌지 않는다. → Task 2 계측 테스트
2. **목록에 곡이 0개:** 가져오기 응답이 `saved=false`면 라이브러리로 돌아가고 `가져올 곡이 없어요.`를 안내한다. 매칭을 호출하지 않는다. → Task 2
3. **매칭 중간 실패(YouTube 한도 등):** 이미 찾은 결과는 남는다. `이어서 찾기`는 실패한 offset부터 다시 호출한다. → Task 2
4. **대상 계정 미연결:** OAuth 연결 뒤 내보내기 흐름으로 돌아와 가져오기를 시작한다. 연결이 취소되면 라이브러리 화면으로 돌아가고 선택은 유지된다. → Task 2
5. **자동 선택 곡을 사용자가 바꾸거나 제외:** 바뀐 선택이 `PUT /matches`에 그대로 실린다. 확인 창의 자동 선택 수가 줄어든다. → Task 1 단위 테스트, Task 3

---

### Task 1: `ExportReview` 순수 로직

**Files:**
- Create: `android/app/src/main/java/com/mptaptap/playlisthub/ExportReview.java`
- Create: `android/app/src/test/java/com/mptaptap/playlisthub/ExportReviewTest.java`
- Modify: `android/gradle/libs.versions.toml`
  - `[libraries]`에 `json = { group = "org.json", name = "json", version = "20240303" }`를 추가한다.
  - JVM 테스트에서 Android의 `org.json` 스텁 대신 실제 구현을 쓰기 위해서다.
- Modify: `android/app/build.gradle.kts`에 `testImplementation(libs.json)`을 추가한다.

**Interfaces:**
- Produces (모두 `final class ExportReview`의 `static` 멤버):
  - `JSONArray autoSelect(JSONArray items, JSONArray selections)`
    - 입력 `selections`를 직접 바꾸지 않고 새 배열을 반환한다.
    - 이미 선택이 있는 `sourceIndex`는 건드리지 않는다.
    - `status=="MATCHED"`이고 `selected.externalId`가 있으면 `{sourceIndex,targetId,excluded:false,label,auto:true}`를 추가한다. `label`은 `"제목 · 아티스트"`다.
    - `status!="NOT_SEARCHED"`이고 `candidates`가 비어 있으면 `{sourceIndex,targetId:"",excluded:true,label:"",auto:true}`를 추가한다.
    - 나머지는 추가하지 않는다.
  - `record Groups(List<JSONObject> needsReview, List<JSONObject> excluded, List<JSONObject> selected)`
    - 각 원소는 리뷰 item이고, 원래 순서를 유지한다.
  - `Groups group(JSONArray items, JSONArray selections)`
    - 선택이 없으면 `needsReview`, 제외로 선택되면 `excluded`, 곡이 선택되면 `selected`에 넣는다.
  - `int autoCount(JSONArray selections)`: `auto==true && !excluded`인 선택 수
  - `String sendLabel(String providerName, JSONObject review, JSONArray selections)`
    - 우선순위: 매칭 미완료면 `곡 후보 찾는 중` → 확인 필요 곡이 있으면 `확인 필요 {n}곡 남음` → 아니면 `{providerName}{조사} {selected}곡 보내기`
    - 조사는 `providerName`이 `Spotify`면 `로`, 그 외에는 `으로`다.
  - `boolean canSend(JSONObject review, JSONArray selections)`
    - `nextOffset<0`, `needsReview` 0곡, `selected` 1곡 이상일 때만 `true`
  - `String transferLine(JSONObject transferItem, JSONArray reviewItems)`
    - `"{제목 · 아티스트} · {상태문구}"`를 반환한다. 곡을 찾지 못하면 `"{sourceIndex+1}번째 곡"`을 쓴다.
    - 상태 문구는 기존 `transfer()`의 문구를 그대로 쓴다: `전송 완료` / `실패 · 다시 시도할 수 있어요` / `전송 대기` / `전송 결과 확인 필요`

- [ ] **Step 1: 실패하는 테스트 작성** (`ExportReviewTest`)
  - `autoSelectPicksMatchedAndExcludesEmpty`
    - 입력: MATCHED+selected(`externalId:"sp1"`), NEEDS_REVIEW(후보 2), UNKNOWN(후보 0), NOT_SEARCHED(후보 0)
    - 결과: 선택 2개. `sp1`은 `auto:true`. index 2는 `excluded:true`. index 1과 3은 선택 없음.
  - `autoSelectKeepsUserChoice`
    - index 0에 `{targetId:"other",auto:false}`가 이미 있으면 그대로 유지된다.
  - `groupOrdersBySection`
    - 위 입력 + autoSelect 결과로 `needsReview`=[1,3], `excluded`=[2], `selected`=[0]
  - `sendLabelStates`
    - `nextOffset:5` → `곡 후보 찾는 중`
    - `nextOffset:-1`이고 확인 필요 1곡 → `확인 필요 1곡 남음`
    - 모두 결정 → `Spotify로 1곡 보내기`
    - 이 세 경우 모두에 대해 `canSend`가 false/false/true임을 단언한다.
  - `autoCountDropsWhenUserExcludes`
    - auto 선택을 `{excluded:true,auto:false}`로 바꾸면 `autoCount`가 1에서 0이 된다.
  - `transferLineUsesSongName`
    - `{sourceIndex:0,status:"ADDED"}` + 리뷰 source `{title:"A",artist:"B"}` → `A · B · 전송 완료`
    - 리뷰에 없는 index 7 → `8번째 곡 · 전송 대기`

- [ ] **Step 2: 실패 확인**
  - Run: `cd android && ./gradlew :app:testDebugUnitTest --tests com.mptaptap.playlisthub.ExportReviewTest`
  - Expected: 컴파일 실패 (`ExportReview` 없음)

- [ ] **Step 3: 의존성 추가 후 `ExportReview` 구현**
  - Interfaces의 시그니처를 그대로 따른다.
  - `HubActivity`의 `song()` 형식과 같도록 패키지 내부 `static String song(JSONObject track)`을 `ExportReview`에 두고, `HubActivity.song()`이 이 메서드를 호출하게 한다.

- [ ] **Step 4: 통과 확인**
  - 같은 명령을 실행한다. Expected: 6 tests PASS

- [ ] **Step 5: 커밋**
  - `git commit -m "feat(export): add export review grouping logic"`

### Task 2: 진입 · 연결 확인 · 가져오기 → 자동 매칭

**Files:**
- Modify: `android/app/src/main/java/com/mptaptap/playlisthub/HubActivity.java`
  - `library()`
  - `SAVED_TEXT`
  - `onCreate` 콜백 분기(`libraryConnect` 처리부)
  - `reload()`
  - `render()`
  - `delivered`의 `account` 케이스와 새 라우트
  - `failure()`
- Test: `android/app/src/androidTest/java/com/mptaptap/playlisthub/HubFlowCheckTest.java`
  - 새 `@Test`를 추가한다. 기존 `importedPlaylistReviewExclusionAndTransferRetryUseConfirmedRows`의 루프백 서버 패턴을 따른다.

**Interfaces:**
- Consumes: Task 1의 `ExportReview.autoSelect`
- Produces:
  - `screen` 값 `"export"`
  - `SAVED_TEXT`에 추가하는 상태 키:
    - `exportStopped` (`"true"`면 자동 이어 찾기 중지)
    - `exportConnect` (`"true"`면 OAuth 복귀 뒤 내보내기 재개)
    - `exportSourceUrl`
  - 기존 키 재사용: `importId`, `targetProvider`, `listTitle`, `libraryProvider`, `selections`
  - 메서드:
    - `private void startExport()`: 선택 검사 → 대상 결정 → 연결 확인
    - `private void beginExportImport()`
    - `private void nextExportMatch(int offset)`
  - 라우트:
    - `exportAccount`: GET `/accounts/me`
    - `exportImport`: POST `/imports/playlists`
    - `exportMatch`: POST `/matches`
    - `exportReview`: GET `/matches/{id}?targetProvider=` (복원용)

- [ ] **Step 1: 실패하는 계측 테스트 작성**
  - 테스트 이름: `libraryExportImportsMatchesAllPagesAndStopsForReview`
  - 가짜 서버 응답:
    - 라이브러리 목록 1개
    - `/accounts/me`: 대상 youtube가 `connected:true`
    - import: 3곡, `saved:true`
    - `/matches` offset 0: `nextOffset:2`
    - `/matches` offset 2: `nextOffset:-1`
  - 단언:
    - 목록 하나를 체크하고 `다른 플랫폼으로 보내기`를 누르면 `/matches`가 offset 0과 2로 정확히 2번 호출된다.
    - `확인이 필요한 곡` 섹션이 보인다.
    - 목록 2개를 체크하고 누르면 `보내기는 한 번에 한 목록씩 할 수 있어요.`가 보이고 import 호출이 0번이다.
    - import가 `saved:false`이면 `/matches` 호출이 0번이고 화면이 라이브러리로 돌아간다.
    - offset 2 호출이 503으로 실패하면 `이어서 찾기`가 보이고, 누르면 offset 2로 다시 호출된다.

- [ ] **Step 2: 실패 확인**
  - Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mptaptap.playlisthub.HubFlowCheckTest#libraryExportImportsMatchesAllPagesAndStopsForReview`
  - Expected: FAIL (버튼 없음)
  - 에뮬레이터가 없으면 `:app:compileDebugAndroidTestJavaWithJavac`만 실행하고, 계측 테스트를 실행하지 못했다고 기록한다.

- [ ] **Step 3: 구현**
  - **`library()`**
    - 기존 주 버튼 문구를 `선택한 목록 앱에 저장`으로 바꾼다.
    - 그 아래에 `addButton(content,"다른 플랫폼으로 보내기",this::startExport)`를 추가한다.
  - **`startExport()`**
    - 선택이 1개가 아니면 `보내기는 한 번에 한 목록씩 할 수 있어요.`를 안내하고 종료한다.
    - `targetProvider`는 `libraryProvider`가 아닌 `PROVIDERS` 원소다.
    - 선택 행의 `sourceUrl`과 `title`(80자 자르기는 `startLibraryImport`와 같은 규칙)을 저장한다.
    - `clearSource()` 후 `exportStopped=false`로 두고 `send("exportAccount","GET","/accounts/me",null)`을 부른다.
  - **`exportAccount` 응답**
    - 대상이 `connected`면 `beginExportImport()`를 부른다.
    - 아니면 `exportConnect=true`로 두고 `authorize(target)`을 부른다.
  - **OAuth 복귀**
    - `onCreate` 콜백 분기: `exportConnect`면 `mode="create"`로 둔다. `libraryConnect`와 같은 방식이다.
    - `account` 케이스: `exportConnect`면 이 값을 지운다. 대상이 연결됐으면 `beginExportImport()`, 아니면 `screen=library`와 기존 연결 실패 안내를 보여준다. `libraryConnect` 분기보다 먼저 처리한다.
  - **`beginExportImport()`**
    - 새 UUID로 `send("exportImport","POST","/imports/playlists",{id,provider,url,title})`를 부른다.
    - `screen=export`로 둔다. 진행 문구는 `목록을 가져오고 있어요`다.
  - **`exportImport` 응답**
    - `saved`가 false거나 items가 0이면 `screen=library`로 돌아가 `가져올 곡이 없어요.`를 안내한다.
    - 아니면 `importId`, `imported`, `listTitle`(비어 있을 때만), `saveId=localPlaylistId`를 저장한다. `setResult(RESULT_OK,…playlistId)`를 호출하고 `nextExportMatch(0)`을 부른다.
  - **`nextExportMatch(offset)`**
    - `send("exportMatch","POST","/matches",{importId,targetProvider,offset})`를 부른다.
  - **`exportMatch` 응답**
    - `review`를 저장하고 `selections = ExportReview.autoSelect(items, selections)`로 갱신한다.
    - `nextOffset>=0`이고 `exportStopped`가 아니면 `nextExportMatch(nextOffset)`을 부른다. 아니면 `render()`한다.
  - **`exportReview` 응답** (복원용)
    - `review`를 저장하고 `autoSelect`한 뒤 `render()`한다. 자동 이어 찾기는 하지 않는다.
  - **`reload()`**
    - `screen=export`이고 `importId`가 있으면 `exportReview`를 보낸다. 이때 `exportStopped=true`로 둔다.
  - **`failure()`**
    - `exportMatch`면 `screen=export`, `exportStopped=true`로 두고 `render()` 후 메시지를 보여준다. `showRetry()`는 호출하지 않는다. `이어서 찾기`가 그 역할을 한다.
    - `exportImport`·`exportAccount`면 `screen=library`, `render()` 후 메시지를 보여준다.
  - **`render()`**
    - `case "export": export(); break;`를 추가한다. 이 단계의 `export()`는 진행 상태만 그린다:
      - `{N}곡 중 {M}곡 찾는 중`, 여기서 M은 `nextOffset`
      - 매칭 진행 중이면 `중단` (`exportStopped=true`)
      - 중단되거나 실패하고 `nextOffset>=0`이면 주 버튼 `이어서 찾기` (`exportStopped=false; nextExportMatch(nextOffset)`)
      - 검수 목록은 Task 3에서 추가한다.

- [ ] **Step 4: 통과 확인**
  - Step 2와 같은 명령을 실행한다. Expected: PASS

- [ ] **Step 5: 커밋**
  - `git commit -m "feat(export): start export from library and auto-match pages"`

### Task 3: 내보내기 검수 화면과 전송

**Files:**
- Modify: `HubActivity.java`
  - `export()`
  - 루트 레이아웃(`onCreate`에서 `NestedScrollView` 아래에 하단 바 추가)
  - `render()` (하단 바 초기화)
  - `reviewSaved` 케이스의 확인 창 문구
- Test: `HubFlowCheckTest.java`에 테스트를 추가한다.

**Interfaces:**
- Consumes:
  - Task 1의 `ExportReview.group`, `sendLabel`, `canSend`, `autoCount`
  - Task 2의 `screen "export"`와 상태 키
  - 기존 메서드: `select(int,JSONObject,boolean)`, `chooseCandidate(JSONObject,int)`, `approveTransfer()`
- Produces:
  - 필드 `private LinearLayout bottomBar`
    - 루트에서 스크롤 영역 아래에 고정된다.
    - `render()`가 매번 `removeAllViews()`와 `GONE` 처리를 한다.
    - `export` 화면만 사용한다.

- [ ] **Step 1: 실패하는 계측 테스트 작성**
  - 테스트 이름: `exportReviewShowsOnlyUndecidedFirstAndSendsWithAutoCount`
  - 가짜 매칭 결과: MATCHED 2곡, NEEDS_REVIEW 1곡(후보 2개), 후보 0개 1곡. `nextOffset:-1`
  - 단언:
    - 하단 버튼이 `확인 필요 1곡 남음`이고 비활성이다.
    - NEEDS_REVIEW 카드에서 `이 곡으로`를 누르면 버튼이 `YouTube Music으로 3곡 보내기`로 바뀌고 활성화된다.
    - 버튼을 누르면 `PUT /matches` 본문의 `selections`가 4개다. 후보 0개인 곡은 `excluded:true`, 나머지 3곡은 `targetId`가 있다.
    - 확인 창 메시지에 `자동 선택 2곡 포함`이 들어 있다.
    - `전송`을 누르면 `POST /transfers`가 1번 호출된다.
    - `선택된 곡 (3)`을 눌러 펼친 뒤 한 곡을 `제외`하면 확인 창이 `자동 선택 1곡 포함`으로 바뀐다.

- [ ] **Step 2: 실패 확인**
  - Task 2와 같은 형식의 명령으로 이 테스트만 실행한다. Expected: FAIL

- [ ] **Step 3: 구현**
  - **`export()` (매칭 완료 또는 중단 상태)**
    - 곡 행은 PR #7의 `R.layout.item_media_row`로 그려서 라이브러리 화면과 모양을 맞춘다.
      - 사용하는 id: `row_artwork`, `row_title`, `row_subtitle`, `row_check`, `row_trailing`
      - 행 아래 버튼(`이 곡으로` 등)은 행 바로 아래 가로 `LinearLayout`에 둔다.
    - 위쪽에 `field(content,"새 목록 이름","listTitle",80,false)`를 둔다.
    - 그 아래로 `ExportReview.group` 결과를 세 섹션으로 그린다.
    - **확인이 필요한 곡 (n):** 카드마다 곡명, 상태 문구, 최상위 후보의 `song()`을 보여준다.
      - `이 곡으로`: 첫 번째 후보로 `select(index, track, false)` 후 `render()`
      - `다른 후보`: `chooseCandidate`
      - `제외`: `select(index,null,true)`
    - **찾지 못한 곡 (n) · 제외됨:** 카드를 누르면 `chooseCandidate`를 연다. 후보가 없으면 제외만 선택할 수 있다.
    - **선택된 곡 (n):** 기본으로 접어 둔다. 펼침 여부는 `exportExpanded` 상태 키에 저장하고 `SAVED_TEXT`에 추가한다.
      - 펼치면 각 곡을 `selectionLabel`과 `다른 후보` 버튼으로 보여준다.
      - 대상이 youtube면 기존 `선택한 YouTube 영상 확인` 버튼도 둔다.
    - 하단 바에는 `addPrimaryButton(bottomBar, ExportReview.sendLabel(...), this::approveTransfer)` 하나만 둔다. `canSend`가 false면 `setEnabled(false)`로 둔다.
  - **사용자 선택 표시:** `select()`가 만드는 선택에 `auto:false`를 명시한다.
  - **`reviewSaved` 확인 창:** `screen=export`일 때 메시지 끝에 `\n자동 선택 {autoCount}곡 포함`을 붙인다.
  - 전송 뒤 `screen=transfer`로 가는 기존 경로는 그대로 쓴다.

- [ ] **Step 4: 통과 확인**
  - Expected: PASS
  - Task 2 테스트도 다시 실행해서 PASS를 확인한다.

- [ ] **Step 5: 커밋**
  - `git commit -m "feat(export): review only undecided tracks and send from bottom bar"`

### Task 4: 전송 결과를 곡 이름으로 표시

**Files:**
- Modify: `HubActivity.java`의 `transfer()`
- Test: `HubFlowCheckTest.java`
  - 기존 `importedPlaylistReviewExclusionAndTransferRetryUseConfirmedRows`의 순번 문구 단언을 곡명 기준으로 바꾼다.

**Interfaces:**
- Consumes: Task 1의 `ExportReview.transferLine`, `requests.data`의 `review`
- Produces: 없음

- [ ] **Step 1: 테스트 수정**
  - 결과 화면에 `{곡명 · 아티스트} · 전송 완료`가 보인다.
  - 내보내기 흐름에서 온 결과에는 `앱의 내 목록에도 사본이 저장됐어요.`가 보인다.
  - 실패 곡 줄이 성공 곡 줄보다 위에 있다.

- [ ] **Step 2: 실패 확인**
  - Expected: FAIL (`1번째 곡 · …`만 보임)

- [ ] **Step 3: `transfer()` 수정**
  - 줄 순서는 `FAILED`·`UNKNOWN`·`PENDING` 뒤에 `ADDED`다.
  - 각 줄은 `ExportReview.transferLine(item, review.items)`로 만든다. `review`가 없으면 기존처럼 순번만 표시하는 것으로 대체된다.
  - 제외 곡 줄도 리뷰의 source로 곡명을 표시한다.
  - 사본 안내는 `transfer`의 `importId`가 이번 내보내기의 `importId`와 같고 `exportSourceUrl`이 있을 때만 보여준다.
  - 기존 결과 화면(영상 변환 경로)에도 곡명 표시가 그대로 적용된다.

- [ ] **Step 4: 통과 확인**
  - 이 테스트와 Task 2·3 테스트를 실행한다. Expected: PASS

- [ ] **Step 5: 커밋**
  - `git commit -m "feat(export): show transfer results by song name"`

### Task 5: 전체 검증과 PR

**Files:** 없음 (검증만)

- [ ] **Step 1: 서버·코어 테스트**
  - Run: `(cd backend && ../android/gradlew test)`
  - Expected: BUILD SUCCESSFUL. 백엔드는 바꾸지 않았으므로 회귀가 없어야 한다.
- [ ] **Step 2: 앱 빌드·린트·단위 테스트**
  - Run: `(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug)`
  - Expected: BUILD SUCCESSFUL. 새 lint 경고가 없어야 한다.
- [ ] **Step 3: 계측 테스트**
  - Run: `(cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mptaptap.playlisthub.HubFlowCheckTest)`
  - 에뮬레이터가 없으면 실행하지 못한 범위로 기록한다. CI는 계측 테스트를 실행하지 않는다.
- [ ] **Step 4: `git diff --check`**
  - Expected: 출력 없음
- [ ] **Step 5: 수동 확인 (가능할 때)**
  - 테스트 계정으로 YouTube → Spotify 목록 하나를 실제로 보낸다.
  - 모의 응답과 실서비스 검증 결과를 구분해서 기록한다.
- [ ] **Step 6: 마무리**
  - `CLAUDE_HANDOFF.md` 작업표에 담당·브랜치를 기입한다.
  - `main` 대상 PR을 만든다(사용자 승인 후). PR 본문에 변경 파일, 검사 결과, 미검증 범위를 적는다.
