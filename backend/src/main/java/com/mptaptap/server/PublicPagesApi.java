package com.mptaptap.server;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public project information; no session, cookies, scripts or third-party page assets. */
@RestController
public class PublicPagesApi {
    private final String supportEmail;
    PublicPagesApi(@Value("${taptap.support-email:}") String configuredEmail) {
        String email=configuredEmail==null?"":configuredEmail.strip();
        supportEmail=email.length()<=254 && email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}")?email:"";
    }

    @GetMapping(value="/",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> home() {
        return page("TapTap · 음악 취향을 이어가는 앱","""
            <section class="hero"><p class="eyebrow">ANDROID · 음악 추천과 목록 관리</p><h1>지금 듣고 싶은 음악,<br>TapTap에서 이어가세요.</h1>
            <p>원하는 분위기를 말하고, 곡을 살펴보고, 내 음악 목록을 만들어요.</p>
            <p class="status">현재는 개발·시연 프로젝트입니다. 실제 음악 서비스 계정 연결·공개 배포·휴대폰 검증이 모두 완료된 상태는 아닙니다.</p></section>
            <section><h2>앱에서 할 수 있는 일</h2><ul>
            <li><strong>추천과 대화</strong> — 한국어·영어의 분위기와 장르 조건을 해석하고, 등록된 40곡 안에서 추천해요. 좋아요·싫어요와 이전 조건을 반영해 다시 고를 수 있어요.</li>
            <li><strong>음악 확인과 내 목록</strong> — 공식 YouTube 플레이어 또는 원본 링크로 확인하고, 목록에 저장·수정·정렬·삭제하거나 텍스트로 공유해요.</li>
            <li><strong>계정과 공개 목록</strong> — 같은 계정으로 서버의 목록을 이어서 관리해요. 직접 공개한 목록은 링크·검색·즐겨찾기로 함께 볼 수 있어요.</li>
            <li><strong>영상 변환과 음악 서비스 이전</strong> — 영상 설명이나 직접 입력한 시간표를 검토해 저장하고, 연결한 Spotify·YouTube의 목록을 가져와 후보를 확인한 뒤 명시적으로 전송해요.</li></ul></section>
            <section><h2>추천과 외부 목록은 따로 처리해요</h2>
            <p>OpenAI는 사용자가 요청한 음악 조건을 해석해요. 실제 추천 곡과 반응 반영은 등록 카탈로그를 사용하는 서버 규칙으로 처리합니다. 가져온 외부 목록·플랫폼 연결 정보는 AI 입력이나 추천 근거로 사용하지 않습니다.</p>
            <p>음악 서비스 이전은 각 서비스의 개발자 설정·계정 허용·권한·할당량에 영향을 받습니다. 이미 만들어진 외부 서비스 목록은 TapTap의 계정 삭제로 지워지지 않습니다.</p>
            <a class="action" href="/privacy">어떤 정보를 처리하는지 확인하기</a></section>
            """+contact());
    }

    @GetMapping(value="/privacy",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> privacy() {
        return page("개인정보 처리 안내 · TapTap","""
            <section class="hero"><p class="eyebrow">2026-10-04 · 현재 구현 기준</p><h1>개인정보 처리 안내</h1>
            <p>TapTap은 음악 추천·목록 관리·사용자가 확인한 음악 서비스 이전을 위한 개발·시연 프로젝트입니다. 이 안내는 현재 코드의 처리 범위를 설명합니다. 공개 운영 전 실제 운영자 정보·연락처·배포 환경의 보관 정책을 확인해 반영해야 합니다.</p></section>
            <section><h2>1. 계정과 내 음악 목록</h2>
            <p>서버에 연결하면 익명 사용자 식별자와 로그인용 세션을 만들고, 가입하면 이메일·표시 이름·비밀번호 해시·가입 시각을 저장합니다. 비밀번호 원문 대신 PBKDF2 해시를 저장하며 서버의 세션 토큰도 해시로 보관합니다. 이메일 확인이나 비밀번호 찾기 기능은 현재 제공하지 않습니다.</p>
            <p>목록 이름·곡 정보와 출처·곡 순서, 좋아요·싫어요, 공개 목록의 즐겨찾기와 처리 시각을 계정 또는 익명 사용자에 연결해 저장합니다. 로그인한 기기는 같은 서버의 계정 데이터를 사용합니다.</p>
            <p>앱에는 선택한 서버·해당 서버의 세션·화면 초안과 기본 모드의 로컬 목록이 보관될 수 있습니다. 요청 재시도와 화면 재생성을 위해 진행 중인 요청·응답을 일시 유지합니다. 최근 최대 8개의 추천 요청 원문은 앱 내부 설정에 저장되어 완료·재실행 후에도 복원될 수 있습니다. 앱 삭제나 서버 설정 해제만으로 서버의 데이터가 삭제되는 것은 아닙니다.</p></section>
            <section><h2>2. 사용자가 요청한 AI 추천</h2>
            <p>OpenAI가 설정된 서버에서 추천을 요청하면 사용자가 직접 입력한 현재 요청, 최대 8개의 이전 대화 요청, 검증된 현재 음악 조건을 OpenAI API로 전송합니다. 기본 추천 규칙은 OpenAI 응답 없이도 동작합니다.</p>
            <p>이메일·비밀번호·음악 서비스 연결 토큰, 곡 카탈로그·곡명·아티스트·좋아요·싫어요·유지 곡 ID는 OpenAI 요청에 포함하지 않습니다. 외부 서비스에서 가져온 목록·검색·매칭 정보도 이 경로에 연결하지 않습니다. 사용자가 추천 입력란에 직접 쓴 내용은 요청에 포함됩니다.</p>
            <p>서버는 AI 호출 한도를 위해 사용자 식별자와 요청 시각을 저장합니다. 추천 문장과 대화 원문을 서버 DB에 저장하는 경로는 없으며, 응답 생성 요청의 <code>store</code> 설정은 <code>false</code>입니다. 이 설정으로 OpenAI의 모든 보관·학습 정책을 보장하는 것은 아닙니다. 외부 서비스의 처리는 해당 서비스 정책도 확인해야 합니다.</p></section>
            <section><h2>3. Google·Spotify 연결</h2>
            <p>사용자가 해당 서비스를 허용하면 서비스 계정 식별자·허용 범위·토큰과 만료 정보가 서버에 보관됩니다. 연결 토큰은 서버의 별도 키를 사용하는 AES-GCM으로 암호화합니다. 일회용 연결 요청은 원래 세션에 연결되며 10분 뒤 만료됩니다.</p>
            <p>Google의 YouTube Data API는 사용자가 선택한 영상의 설명·길이, 연결 계정의 목록과 곡 정보를 조회하고, 사용자가 확인한 전송 대상의 비공개 목록을 만들 때 사용합니다. Spotify도 목록 읽기·곡 후보 검색·확인한 전송에 사용합니다. Google·다른 플랫폼의 메타데이터는 OpenAI에 전송하거나 AI 추천·학습에 사용하지 않습니다.</p>
            <p>가져오기·변환·매칭·변경 미리 보기에는 제목·아티스트·앨범·길이·곡/목록 ID·ISRC·원본 링크·행과 시작 시각·검토/제외 내용·전송 상태가 포함될 수 있습니다. 직접 입력한 시간표도 검토와 재진입을 위해 보관할 수 있습니다. 자동으로 모든 음악을 이전하지 않고, 사용자의 검토와 전송 확인 뒤 선택한 곡만 외부 서비스에 보냅니다.</p></section>
            <section><h2>4. 보관 기간과 갱신</h2>
            <p>외부 메타데이터는 원본 조회 기준 30일 뒤 제거하거나, 다시 조회하기 위한 플랫폼·원본 ID/URL·위치 등 최소 참조와 갱신 필요 상태만 남깁니다. 저장 목록과 공개 스냅샷의 만료된 제목·아티스트·원문 행도 제거 대상입니다. 수동 제목 수정이나 다시 저장만으로 이 기간을 연장하지 않습니다.</p>
            <p>만료 정리는 관련 조회와 주기적 정리에서 적용됩니다. 일반 추천 카탈로그는 이 외부 메타데이터 만료 대상이 아닙니다. 연결 정보는 연결 해제·계정 삭제 전까지 보관할 수 있고, 일반 계정·목록·반응과 최소 원본 참조에는 현재 별도의 자동 만료 기간이 없습니다.</p>
            <p>로그인 실패 제한에는 이메일과 접속 주소의 해시를 메모리에서 일시 사용합니다. 실제 운영 환경의 접근 기록·백업과 그 보관 기간은 아직 이 프로젝트에서 확정하지 않았습니다.</p></section>
            <section><h2>5. 공개 목록과 다른 서비스</h2>
            <p>직접 공개를 선택하면 목록 제목·표시 이름(미가입 사용자는 익명)·곡 정보/출처의 스냅샷이 공개 링크와 검색에 표시됩니다. 계정 이메일과 로그인 토큰은 공개 목록에 표시하지 않습니다. 즐겨찾기 순위는 TapTap 안의 즐겨찾기 수로 계산합니다.</p>
            <p>공개 철회나 원래 목록 삭제는 기존 공개 링크를 닫습니다. 다른 사용자가 이미 복사한 독립 목록, 텍스트로 공유한 내용, 외부 음악 서비스에 만들어진 목록까지 삭제하는 것은 아닙니다.</p>
            <p>공식 YouTube 플레이어·썸네일·외부 링크를 사용할 때 해당 서비스로 네트워크 요청이 발생할 수 있습니다. 그 서비스의 정보 처리·보관은 해당 정책을 확인해야 합니다. 이 두 안내 페이지에는 외부 스크립트·쿠키·방문 분석 도구를 넣지 않습니다.</p></section>
            <section><h2>6. 앱에서 정보 관리하기</h2><ul>
            <li><strong>내 목록·반응</strong>에서 곡·목록을 삭제하거나 반응을 취소·초기화할 수 있습니다.</li>
            <li><strong>내 계정 → 계정 삭제</strong>에서 비밀번호를 확인하면 서버의 계정·세션·목록·반응·연결 정보·소유한 작업 기록을 삭제합니다. 로그아웃은 현재 기기의 세션만 해제하며 계정 데이터를 삭제하지 않습니다.</li>
            <li><strong>내 계정 → 연결 해제</strong>는 TapTap의 해당 서비스 연결 정보·관련 작업과 본인 목록/공개 스냅샷의 해당 외부 곡 정보를 제거합니다. 다른 사용자의 독립 복사본과 외부 서비스 목록은 유지됩니다. 서비스 자체의 앱 접근 권한은 안내된 계정 관리 경로에서도 확인·철회할 수 있습니다.</li>
            <li><strong>목록 공개 설정 → 비공개로 전환</strong>으로 공개 링크를 철회할 수 있습니다.</li>
            <li><strong>이전 음악 목록과 전송 → 기록만 삭제</strong>는 작업 기록만 삭제하며 내 저장 목록과 외부 서비스 목록은 유지됩니다.</li></ul></section>
            """+contact());
    }

    private String contact() {
        String message=supportEmail.isEmpty()?"<p>운영자 문의 이메일은 아직 설정되지 않았습니다. 현재는 위의 앱 내 계정·연결·목록 관리 경로를 이용할 수 있습니다.</p>":
            "<p>운영자가 설정한 문의 이메일: <a href=\"mailto:"+URLEncoder.encode(supportEmail,StandardCharsets.UTF_8).replace("%40","@")+"\">"+escape(supportEmail)+"</a></p><p>이메일 링크는 사용자의 메일 앱을 열며 TapTap 서버에서 메일을 보내지 않습니다.</p>";
        return "<section><h2>문의와 데이터 관리</h2>"+message+"<p>앱의 설정에서 ‘계정 · 플랫폼 연결’을 열어 계정과 연결 정보를 관리할 수 있습니다.</p></section>";
    }

    private static ResponseEntity<String> page(String title,String content) {
        String html="""
            <!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>%s</title><style>
            :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#F6F7FB;color:#1E293B;font:17px/1.7 system-ui,-apple-system,sans-serif;overflow-wrap:anywhere}
            header,main,footer{max-width:880px;margin:auto;padding:20px}header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}nav{display:flex;gap:8px;flex-wrap:wrap}
            a{color:#C53442;text-underline-offset:4px}nav a,.action{display:inline-flex;align-items:center;min-height:48px;padding:8px 12px}.brand{font-size:24px;font-weight:750;text-decoration:none}
            section{background:#fff;border:1px solid #DCE3EF;border-radius:20px;padding:24px;margin-bottom:18px}h1{font-size:clamp(28px,5vw,42px);line-height:1.3;margin:12px 0}h2{font-size:22px;line-height:1.4;margin:0 0 12px}p{margin:12px 0}ul{padding-left:24px}li{margin:12px 0}
            .eyebrow{color:#13746B;font-size:14px;font-weight:650}.status{padding:16px;border-radius:12px;background:#FFE8EB}.action{background:#C53442;color:white;border-radius:12px;text-decoration:none}footer{color:#5D6B80;font-size:14px}
            @media(max-width:480px){header,main,footer{padding:16px}section{padding:20px}nav a{padding:8px}body{font-size:16px}}
            </style></head><body><header><a class="brand" href="/">TapTap</a><nav aria-label="프로젝트 안내"><a href="/">앱 소개</a><a href="/privacy">개인정보 처리 안내</a></nav></header>
            <main>%s</main><footer>TapTap · 개발·시연 프로젝트 · <a href="/privacy">개인정보 처리 안내</a></footer></body></html>
            """.formatted(escape(title),content);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
            .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("Cache-Control","no-store").body(html);
    }
    private static String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
