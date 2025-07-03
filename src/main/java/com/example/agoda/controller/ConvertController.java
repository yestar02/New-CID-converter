// src/main/java/com/example/agoda/controller/ConvertController.java

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

@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1", 1833982),
        // ... 생략 ...
        new CidEntry("에어서울", 1800120)
    );

    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드","https://www.agoda.com/ko-kr/kbcard"),
        // ... 생략 ...
        new AffiliateLink("에어서울","https://www.agoda.com/ko-kr/airseoul")
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

        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();

        for (CidEntry entry : cidList) {
            results.add(fetchSequentially(url, entry));
        }

        String hotelName = results.stream()
            .map(LinkInfo::getHotel)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse("호텔명 없음");

        LinkInfo cheapest = results.stream()
            .filter(r -> !r.isSoldOut() && r.getPrice() > 0)
            .min(Comparator.comparingDouble(LinkInfo::getPrice))
            .orElse(null);

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("hotel", hotelName);
        resp.put("priced", results);
        resp.put("cheapest", cheapest);
        resp.put("affiliateLinks", AFFILIATES);
        resp.put("totalCids", cidList.size());
        resp.put("collectedResults", results.size());

        return ResponseEntity.ok(resp);
    }

    private LinkInfo fetchSequentially(String baseUrl, CidEntry entry) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        JsonNode root = fetchSecondaryDataJson(modUrl);

        String hotel = root.path("hotelInfo").path("name").asText(null);
        double price = root.path("mosaicInitData")
                           .path("discount")
                           .path("cheapestPrice")
                           .asDouble(0);
        String currency = root.path("mosaicInitData")
                              .path("discount")
                              .path("currency")
                              .asText("UNKNOWN");
        boolean soldOut = price == 0;

        // 로그 출력
        System.out.printf(
            soldOut
                ? "✗ %s (CID: %d) - 품절 (currency: %s)%n"
                : "✓ %s (CID: %d) - 가격: %.2f %s%n",
            entry.label(), entry.cid(), price, currency
        );

        return new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel);
    }

    private JsonNode fetchSecondaryDataJson(String hotelPageUrl) {
        try {
            Document doc = Jsoup.connect(hotelPageUrl)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                .header("ag-language-locale", "ko-kr")
                .header("User-Agent", "Mozilla/5.0")
                .timeout((int) Duration.ofSeconds(15).toMillis())
                .get();

            Element script = doc.selectFirst("script[data-selenium=script-initparam]");
            String initJson = script != null
                ? (script.data().isEmpty() ? script.text() : script.data())
                : "";
            String apiPath = initJson.split("apiUrl\\s*=\\s*\"")[1]
                                     .split("\"")[0]
                                     .replace("&amp;", "&");
            String apiUrl = "https://www.agoda.com" + apiPath;

            System.out.println("API Request URL: " + apiUrl);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                .header("ag-language-locale", "ko-kr")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", hotelPageUrl)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(res.body());
        } catch (Exception e) {
            throw new RuntimeException("API 호출 실패", e);
        }
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
