package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ConvertController {

    // 고정 CID 목록 (전체 유지)
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1",    1833982),
        new CidEntry("구글 지도 2",    1917614),
        new CidEntry("구글 지도 3",    1829668),
        new CidEntry("구글 검색 1",    1908617),
        new CidEntry("구글 검색 2",    1921868),
        new CidEntry("구글 검색 3",    1922847),
        new CidEntry("네이버",         1881505),
        new CidEntry("Bing",          1911217),
        new CidEntry("다음",          1908762),
        new CidEntry("DuckDuckGo",    1895204),
        new CidEntry("국민카드",       1563295),
        new CidEntry("우리카드",       1654104),
        new CidEntry("우리카드(마스터)",1932810),
        new CidEntry("현대카드",       1768446),
        new CidEntry("BC카드",         1748498),
        new CidEntry("신한카드",       1760133),
        new CidEntry("신한카드(마스터)",1917257),
        new CidEntry("토스",           1917334),
        new CidEntry("하나카드",       1729471),
        new CidEntry("카카오페이",     1845109),
        new CidEntry("마스터카드",     1889572),
        new CidEntry("유니온페이",     1801110),
        new CidEntry("비자",           1889319),
        new CidEntry("대한항공(적립)", 1904827),
        new CidEntry("아시아나항공(적립)",1806212),
        new CidEntry("에어서울",       1800120)
    );

    // 제휴 링크 목록
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",       "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",       "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)", "https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",       "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("BC카드",         "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한카드",       "https://www.agoda.com/ko-kr/shinhancard"),
        new CidEntry("신한카드(마스터)", "https://www.agoda.com/ko-kr/shinhanmaster"),
        new AffiliateLink("토스",           "https://www.agoda.com/ko-kr/tossbank"),
        new AffiliateLink("하나카드",       "https://www.agoda.com/ko-kr/hanacard"),
        new AffiliateLink("카카오페이",     "https://www.agoda.com/ko-kr/kakaopay"),
        new AffiliateLink("마스터카드",     "https://www.agoda.com/ko-kr/krmastercard"),
        new AffiliateLink("유니온페이",     "https://www.agoda.com/ko-kr/unionpayKR"),
        new AffiliateLink("비자",           "https://www.agoda.com/ko-kr/visakorea"),
        new AffiliateLink("대한항공",       "https://www.agoda.com/ko-kr/koreanair"),
        new AffiliateLink("아시아나항공",   "https://www.agoda.com/ko-kr/flyasiana"),
        new AffiliateLink("에어서울",       "https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
        .build();

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        try {
            if (body == null || body.get("url") == null || body.get("url").isBlank()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "유효한 URL을 입력해주세요.");
                return ResponseEntity.badRequest().body(error);
            }

            String url = body.get("url");
            if (!url.contains("agoda.com") || !url.contains("cid=")) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "아고다 호텔 상세 URL이 아닙니다.");
                return ResponseEntity.badRequest().body(error);
            }

            List<CidEntry> cidList = buildCidList();
            List<LinkInfo> results = new ArrayList<>();

            // 순차 처리로 결과 순서 보장
            for (CidEntry entry : cidList) {
                fetchWithRetry(url, entry, results);
            }

            String hotelName = results.stream()
                .map(LinkInfo::hotel)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("호텔명 없음");

            LinkInfo cheapest = results.stream()
                .filter(r -> !r.soldOut() && r.price() > 0)
                .min(Comparator.comparingDouble(LinkInfo::price))
                .orElse(null);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("hotel", hotelName);
            resp.put("priced", results);
            resp.put("cheapest", cheapest);
            resp.put("affiliateLinks", AFFILIATES);

            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "서버 내부 오류가 발생했습니다.");
            return ResponseEntity.internalServerError().body(error);
        }
    }

    private void fetchWithRetry(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        int maxAttempts = 3;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                JsonNode root = fetchSecondaryDataJson(modUrl);

                // 호텔명 추출
                String hotel = root.path("hotelInfo").path("name").asText(null);

                // mosaicInitData.discount.cheapestPrice 만 사용 (숫자 타입)
                double price = root.path("mosaicInitData")
                                   .path("discount")
                                   .path("cheapestPrice")
                                   .asDouble(0);

                boolean soldOut = price == 0;
                results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel));
                return;

            } catch (Exception e) {
                if (i == maxAttempts) {
                    results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null));
                } else {
                    try { Thread.sleep(1000L * i); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private JsonNode fetchSecondaryDataJson(String hotelPageUrl) throws Exception {
        // 매번 새로운 HttpClient 생성 (세션 격리)
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
            .build();

        // 1) HTML 파싱
        Document doc = Jsoup.connect(hotelPageUrl)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent", "Mozilla/5.0")
            .timeout((int)Duration.ofSeconds(15).toMillis())
            .get();

        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        if (script == null) {
            throw new IllegalStateException("script-initparam 태그를 찾을 수 없습니다: " + hotelPageUrl);
        }

        String content = script.data().isEmpty() ? script.text() : script.data();
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("script 내용이 비어있습니다: " + hotelPageUrl);
        }

        // 2) apiUrl 추출
        int start = content.indexOf("apiUrl = \"");
        if (start < 0) {
            throw new IllegalStateException("apiUrl 패턴을 찾을 수 없습니다: " + hotelPageUrl);
        }
        start += "apiUrl = \"".length();
        int end = content.indexOf("\"", start);
        if (end < 0) {
            throw new IllegalStateException("apiUrl 문자열 끝을 찾을 수 없습니다: " + hotelPageUrl);
        }

        String apiPath = content.substring(start, end).replace("&amp;", "&");

        // 한화 표시 및 상세 가격 모드 보장
        if (!apiPath.contains("currencyCode=")) apiPath += "&currencyCode=KRW";
        if (!apiPath.contains("price_view="))   apiPath += "&price_view=2";

        String apiUrl = "https://www.agoda.com" + apiPath;

        // 3) API 호출
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", hotelPageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        return mapper.readTree(response.body());
    }

    private List<CidEntry> buildCidList() {
        // 무작위 CID 5개 추가
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

    public static record LinkInfo(
        String label,
        int cid,
        String url,
        double price,
        boolean soldOut,
        String hotel
    ) {}

    public static record AffiliateLink(String label, String url) {}
}
