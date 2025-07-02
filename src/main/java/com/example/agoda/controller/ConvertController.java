package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.*;

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
        new AffiliateLink("국민카드","https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드","https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)","https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드","https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("BC카드","https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한카드","https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("신한카드(마스터)","https://www.agoda.com/ko-kr/shinhanmaster"),
        new AffiliateLink("토스","https://www.agoda.com/ko-kr/tossbank"),
        new AffiliateLink("하나카드","https://www.agoda.com/ko-kr/hanacard"),
        new AffiliateLink("카카오페이","https://www.agoda.com/ko-kr/kakaopay"),
        new AffiliateLink("마스터카드","https://www.agoda.com/ko-kr/krmastercard"),
        new AffiliateLink("유니온페이","https://www.agoda.com/ko-kr/unionpayKR"),
        new AffiliateLink("비자","https://www.agoda.com/ko-kr/visakorea"),
        new AffiliateLink("대한항공","https://www.agoda.com/ko-kr/koreanair"),
        new AffiliateLink("아시아나항공","https://www.agoda.com/ko-kr/flyasiana"),
        new AffiliateLink("에어서울","https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "주소를 입력해주세요."));
        }
        if (!url.contains("agoda.com") || !url.contains("cid=")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "유효한 아고다 상세 URL을 입력해주세요."));
        }

        // 무작위 CID 5개 추가
        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = Collections.synchronizedList(new ArrayList<>());

        // 병렬 호출 + 재시도
        CompletableFuture<?>[] futures = cidList.stream()
            .map(entry -> CompletableFuture.runAsync(() -> fetchWithRetry(url, entry, results), scheduler))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures)
            .completeOnTimeout(null, 20, TimeUnit.SECONDS)
            .join();

        // 호텔명 취합
        String hotelName = results.stream()
            .map(LinkInfo::getHotel)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse("호텔명 없음");

        // 최저가 선정
        LinkInfo cheapest = results.stream()
            .filter(r -> !r.isSoldOut() && r.getPrice() > 0)
            .min(Comparator.comparingDouble(LinkInfo::getPrice))
            .orElse(null);

        Map<String,Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("hotel", hotelName);
        resp.put("priced", results);
        resp.put("cheapest", cheapest);
        resp.put("affiliateLinks", AFFILIATES);
        return ResponseEntity.ok(resp);
    }

    private void fetchWithRetry(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        int maxAttempts = 3;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                JsonNode root = fetchSecondaryDataJson(modUrl);

                // 호텔명
                String hotel = root.path("hotelInfo").path("name").asText(null);

                // 숫자로만 된 시작가 추출 (mosaicInitData.discount.cheapestPrice)
                double price = root.path("mosaicInitData")
                                   .path("discount")
                                   .path("cheapestPrice")
                                   .asDouble(0);
                // 콤마 포함 문자열이 필요하다면:
                // String raw = root.path("stickyFooter").path("discount").path("cheapestPriceWithCurrency").asText("");
                // String numeric = raw.replaceAll("[^0-9]", "");
                // price = numeric.isBlank() ? 0 : Double.parseDouble(numeric);

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
        // HTML 파싱 시 UTF-8 처리
        Document doc = Jsoup.connect(hotelPageUrl)
            .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .timeout((int)Duration.ofSeconds(15).toMillis())
            .get();
        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        String content = script != null
            ? (script.data().isEmpty() ? script.text() : script.data())
            : "";
        String apiPath = content.split("apiUrl\\s*=\\s*\"")[1]
                                .split("\"")[0]
                                .replace("&amp;","&");
        String apiUrl = "https://www.agoda.com" + apiPath;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", hotelPageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    public static record AffiliateLink(String label, String url) {}
}
