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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class ConvertController {

    // 고정 CID 목록 (예시 5개만 사용)
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도",    1833982),
        new CidEntry("구글 검색",    1908617),
        new CidEntry("네이버",       1881505),
        new CidEntry("Bing",        1911217),
        new CidEntry("다음",        1908762)
    );

    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",   "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",   "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)", "https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",   "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("에어서울",   "https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

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

        List<CidEntry> cidList = buildCidList();  // 무작위 CID 5개
        List<LinkInfo> results = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<?>[] futures = cidList.stream()
            .map(entry -> CompletableFuture.runAsync(() -> fetchWithRetry(url, entry, results), scheduler))
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures)
            .completeOnTimeout(null, 15, TimeUnit.SECONDS)
            .join();

        String hotelName = results.stream()
            .map(LinkInfo::getHotel)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse("호텔명 없음");

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

                // 호텔명 추출 (루트 hotelInfo.name)
                String hotel = root.path("hotelInfo").path("name").asText(null);

                // mosaicInitData.discount.cheapestPrice (숫자만)
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
                    try { Thread.sleep(500L * i); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private JsonNode fetchSecondaryDataJson(String hotelPageUrl) throws Exception {
        Document doc = Jsoup.connect(hotelPageUrl)
            .header("Accept-Language","ko-KR")
            .timeout((int)Duration.ofSeconds(10).toMillis())
            .get();
        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        String content = script != null
            ? (script.data().isEmpty() ? script.text() : script.data())
            : "";
        String apiPath = content.split("apiUrl\\s*=\\s*\"")[1]
                                .split("\"")[0]
                                .replace("&amp;","&");
        String apiUrl = "https://www.agoda.com" + apiPath;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language","ko-KR")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(res.body());
    }

    private List<CidEntry> buildCidList() {
        // 무작위 CID 5개 + STATIC_CIDS
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
            this.label = label; this.cid = cid; this.url = url;
            this.price = price; this.soldOut = soldOut; this.hotel = hotel;
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
