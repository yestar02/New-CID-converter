package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ConvertController {

    // 고정 CID 목록 (전체 유지)
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1", 1833982),
        new CidEntry("구글 지도 2", 1917614),
        new CidEntry("구글 지도 3", 1829668),
        new CidEntry("구글 검색 1", 1908617),
        new CidEntry("구글 검색 2", 1921868),
        new CidEntry("구글 검색 3", 1922847),
        new CidEntry("네이버", 1881505),
        new CidEntry("Bing", 1911217),
        new CidEntry("다음", 1908762),
        new CidEntry("DuckDuckGo", 1895204),
        new CidEntry("국민카드", 1563295),
        new CidEntry("우리카드", 1654104),
        new CidEntry("우리카드(마스터)", 1932810),
        new CidEntry("현대카드", 1768446),
        new CidEntry("BC카드", 1748498),
        new CidEntry("신한카드", 1760133),
        new CidEntry("신한카드(마스터)", 1917257),
        new CidEntry("토스", 1917334),
        new CidEntry("하나카드", 1729471),
        new CidEntry("카카오페이", 1845109),
        new CidEntry("마스터카드", 1889572),
        new CidEntry("유니온페이", 1801110),
        new CidEntry("비자", 1889319),
        new CidEntry("대한항공(적립)", 1904827),
        new CidEntry("아시아나항공(적립)", 1806212),
        new CidEntry("에어서울", 1800120)
    );

    // 제휴 링크 목록
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드", "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드", "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)", "https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드", "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("BC카드", "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한카드", "https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("신한카드(마스터)", "https://www.agoda.com/ko-kr/shinhanmaster"),
        new AffiliateLink("토스", "https://www.agoda.com/ko-kr/tossbank"),
        new AffiliateLink("하나카드", "https://www.agoda.com/ko-kr/hanacard"),
        new AffiliateLink("카카오페이", "https://www.agoda.com/ko-kr/kakaopay"),
        new AffiliateLink("마스터카드", "https://www.agoda.com/ko-kr/krmastercard"),
        new AffiliateLink("유니온페이", "https://www.agoda.com/ko-kr/unionpayKR"),
        new AffiliateLink("비자", "https://www.agoda.com/ko-kr/visakorea"),
        new AffiliateLink("대한항공", "https://www.agoda.com/ko-kr/koreanair"),
        new AffiliateLink("아시아나항공", "https://www.agoda.com/ko-kr/flyasiana"),
        new AffiliateLink("에어서울", "https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "주소를 입력해주세요."));
        }
        if (!url.contains("agoda.com") || !url.contains("cid=")) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "유효한 아고다 상세 URL을 입력해주세요."));
        }

        String currency = extractCurrencyFromUrl(url);

        // 1) 프로그램 세션으로 한 번 접속하여 쿠키 수집 및 수정
        System.out.println("=== 프로그램 세션으로 쿠키 수집 ===");
        Map<String, String> sessionCookies = null;
        try {
            // 더 완전한 브라우저 헤더로 봇 탐지 우회
            Connection.Response response = Jsoup.connect(url)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                .header("Cache-Control", "max-age=0")
                .header("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("sec-fetch-dest", "document")
                .header("sec-fetch-mode", "navigate")
                .header("sec-fetch-site", "none")
                .header("sec-fetch-user", "?1")
                .header("upgrade-insecure-requests", "1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .timeout(10000)  // 30초로 타임아웃 증가
                .ignoreHttpErrors(true)  // HTTP 오류 무시
                .followRedirects(true)   // 리디렉션 따라가기
                .execute();

            // 404 응답 확인
            if (response.statusCode() == 404 || response.url().toString().contains("pagenotfound")) {
                throw new Exception("URL이 유효하지 않거나 접근이 차단되었습니다. URL을 확인해주세요.");
            }

            sessionCookies = new HashMap<>(response.cookies());
            
            // 쿠키가 없으면 오류
            if (sessionCookies.isEmpty()) {
                throw new Exception("세션 쿠키를 받지 못했습니다.");
            }
            
            // 통화 관련 쿠키를 KRW로 강제 수정
            if (sessionCookies.containsKey("agoda.version.03")) {
                String versionCookie = sessionCookies.get("agoda.version.03");
                String originalCurrency = versionCookie.contains("CurLabel=") 
                    ? versionCookie.split("CurLabel=")[1].split("&")[0] 
                    : "UNKNOWN";
                    
                versionCookie = versionCookie.replaceAll("CurLabel=\\w+", "CurLabel=KRW");
                sessionCookies.put("agoda.version.03", versionCookie);
                
                System.out.printf("통화 쿠키 수정: %s → KRW%n", originalCurrency);
            }
            
            // 가격뷰 쿠키 강제 설정
            sessionCookies.put("agoda.price.01", "PriceView=2");
            
            System.out.printf("세션 쿠키 수집 완료: %d개%n", sessionCookies.size());
            System.out.printf("최종 응답 URL: %s%n", response.url().toString());
            
        } catch (Exception e) {
            System.out.println("세션 쿠키 수집 실패: " + e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "URL에 접근할 수 없습니다: " + e.getMessage()));
        }

        // 2) 세션 쿠키로 초기 호텔명과 가격 가져오기
        System.out.println("=== 원본 주소에서 호텔명과 가격 가져오기 ===");
        String initialHotel = "호텔명 없음";
        double initialPrice = 0;
        String initialCurrency = "UNKNOWN";
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode initialRoot = fetchSecondaryDataJsonWithSession(url, currency, "INITIAL", sessionCookies);
                initialHotel = initialRoot.path("hotelInfo").path("name").asText("호텔명 없음");
                initialPrice = initialRoot.path("mosaicInitData")
                                          .path("discount")
                                          .path("cheapestPrice")
                                          .asDouble(0);
                initialCurrency = initialRoot.path("mosaicInitData")
                                             .path("discount")
                                             .path("currency")
                                             .asText("UNKNOWN");
                
                System.out.printf("원본 호텔명: %s, 원본 가격: %.2f (currency: %s)%n", 
                    initialHotel, initialPrice, initialCurrency);
                
                if (initialPrice > 0) break;
                if (attempt < maxAttempts) Thread.sleep(1000L * attempt);
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    System.out.println("초기 가격 정보를 가져오는데 실패했습니다: " + e.getMessage());
                }
            }
        }

        // 3) 동일한 세션으로 CID별 가격 수집
        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();

        System.out.println("\n=== 동일한 세션으로 CID별 가격 수집 시작 ===");
        System.out.println("총 " + cidList.size() + "개 CID로 순차적 가격 수집 시작...");

        for (int i = 0; i < cidList.size(); i++) {
            CidEntry entry = cidList.get(i);
            System.out.printf("(%d/%d) %s 처리 중...%n", i + 1, cidList.size(), entry.label());
            results.add(fetchSequentiallyWithSession(url, entry, sessionCookies));
        }
        System.out.println("모든 CID 가격 수집 완료!");

        String hotelName = results.stream()
            .map(LinkInfo::getHotel)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(initialHotel);

        LinkInfo cheapest = results.stream()
            .filter(r -> !r.isSoldOut() && r.getPrice() > 0)
            .min(Comparator.comparingDouble(LinkInfo::getPrice))
            .orElse(null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("hotel", hotelName);
        resp.put("initialPrice", initialPrice);
        resp.put("initialCurrency", initialCurrency);  // 초기 통화 정보 추가
        resp.put("priced", results);
        resp.put("cheapest", cheapest);
        resp.put("affiliateLinks", AFFILIATES);
        resp.put("totalCids", cidList.size());
        resp.put("collectedResults", results.size());

        return ResponseEntity.ok(resp);
    }

    private LinkInfo fetchSequentiallyWithSession(String baseUrl, CidEntry entry, Map<String, String> sessionCookies) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        String currency = extractCurrencyFromUrl(baseUrl);

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode root = fetchSecondaryDataJsonWithSession(modUrl, currency, entry.label(), sessionCookies);

                String hotel = root.path("hotelInfo").path("name").asText(null);
                double price = root.path("mosaicInitData")
                                   .path("discount")
                                   .path("cheapestPrice")
                                   .asDouble(0);
                String apiCurrency = root.path("mosaicInitData")
                                         .path("discount")
                                         .path("currency")
                                         .asText("UNKNOWN");

                boolean soldOut = price == 0;
                System.out.printf(
                    soldOut
                        ? "✗ %s (CID: %d) - 품절 (currency: %s)%n"
                        : "✓ %s (CID: %d) - 가격: %.2f (currency: %s)%n",
                    entry.label(), entry.cid(), price, apiCurrency
                );

                return new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel);

            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    System.out.printf("✗ %s (CID: %d) - 실패: %s%n", entry.label(), entry.cid(), e.getMessage());
                    return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null);
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null);
    }

    private JsonNode fetchSecondaryDataJsonWithSession(String hotelPageUrl, String currency, String debugLabel, Map<String, String> sessionCookies) throws Exception {
        // Jsoup으로 HTML 파싱하되, 세션 쿠키는 이미 있으므로 API URL만 추출
        Document doc = Jsoup.connect(hotelPageUrl)
            .cookies(sessionCookies)  // 기존 세션 쿠키 사용
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .timeout((int) Duration.ofSeconds(15).toMillis())
            .get();

        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        String content = script != null
            ? (script.data().isEmpty() ? script.text() : script.data())
            : "";
        String apiPath = content.split("apiUrl\\s*=\\s*\"")[1]
                                .split("\"")[0]
                                .replace("&amp;", "&");
        String apiUrl = "https://www.agoda.com" + apiPath;

        // API 요청 URL 출력 (디버깅용)
        System.out.printf("[%s] API Request URL: %s%n", debugLabel, apiUrl);

        // 세션 쿠키를 사용하여 API 요청
        String cookieHeader = sessionCookies.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("; "));

        System.out.printf("[%s] 사용할 세션 쿠키: %s%n", debugLabel, 
            cookieHeader.length() > 200 ? cookieHeader.substring(0, 200) + "..." : cookieHeader);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept", "*/*")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")
            .header("Cookie", cookieHeader)  // 세션 쿠키 사용
            .header("cr-currency-code", "KRW")
            .header("cr-currency-id", "26")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", hotelPageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        // 요청 헤더 전체 출력 (첫 번째 요청만)
        if ("INITIAL".equals(debugLabel)) {
            System.out.printf("[%s] === Request Headers ===\n", debugLabel);
            request.headers().map().forEach((key, values) -> {
                for (String value : values) {
                    System.out.printf("[%s] %s: %s\n", debugLabel, key, value);
                }
            });
            System.out.printf("[%s] === End of Headers ===\n", debugLabel);
        }

        HttpResponse<String> apiResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return mapper.readTree(apiResponse.body());
    }

    private String extractCurrencyFromUrl(String url) {
        if (url.contains("currencyCode=")) {
            return url.split("currencyCode=")[1].split("&")[0].toUpperCase();
        }
        if (url.contains("currency=")) {
            return url.split("currency=")[1].split("&")[0].toUpperCase();
        }
        return "KRW";
    }

    private List<CidEntry> buildCidList() {
        Set<Integer> rnd = new LinkedHashSet<>();
        Random rand = new Random();
        while (rnd.size() < 5) {
            rnd.add(rand.nextInt(2000000 - 1800000 + 1) + 1800000);
        }
        List<CidEntry> list = new ArrayList<>(STATIC_CIDS);
        rnd.forEach(cid -> list.add(new CidEntry("AUTO-" + cid, cid)));
        return list;
    }

    public static record CidEntry(String label, int cid) {}
    public static record AffiliateLink(String label, String url) {}

    public static class LinkInfo {
        private final String label;
        private final int cid;
        private final String url;
        private final double price;
        private final boolean soldOut;
        private final String hotel;

        public LinkInfo(String label, int cid, String url, double price, boolean soldOut, String hotel) {
            this.label = label;
            this.cid = cid;
            this.url = url;
            this.price = price;
            this.soldOut = soldOut;
            this.hotel = hotel;
        }

        public String getLabel() { return label; }
        public int getCid() { return cid; }
        public String getUrl() { return url; }
        public double getPrice() { return price; }
        public boolean isSoldOut() { return soldOut; }
        public String getHotel() { return hotel; }
    }
}
