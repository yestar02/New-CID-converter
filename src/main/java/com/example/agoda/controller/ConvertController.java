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

    // 고정 CID 목록
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

        System.out.println("총 " + cidList.size() + "개 CID로 순차적 가격 수집 시작...");

        for (int i = 0; i < cidList.size(); i++) {
            CidEntry entry = cidList.get(i);
            System.out.printf("(%d/%d) %s 처리 중...%n", i + 1, cidList.size(), entry.label());
            results.add(fetchSequentially(url, entry));
        }
        System.out.println("모든 CID 가격 수집 완료!");

        String hotelName = results.stream()
                                  .map(LinkInfo::getHotel)
                                  .filter(Objects::nonNull)
                                  .findFirst()
                                  .orElse("호텔명 없음");

        LinkInfo cheapest = results.stream()
                                   .filter(r -> !r.isSoldOut() && r.getPrice() > 0)
                                   .min(Comparator.comparingDouble(LinkInfo::getPrice))
                                   .orElse(null);

        Map<String, Object> resp = new LinkedHashMap<>();
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
        String currency = extractCurrencyFromUrl(baseUrl);

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode root = fetchSecondaryDataJson(modUrl, currency);

                String hotel = root.path("hotelInfo").path("name").asText(null);
                String actualCurrency = root.path("searchInfo").path("currency").asText(currency);
                double price = extractPrice(root);

                boolean soldOut = price == 0;
                System.out.printf(
                    soldOut
                        ? "✗ %s (CID: %d) - 품절%n"
                        : "✓ %s (CID: %d) - 가격: %.2f %s%n",
                    entry.label(), entry.cid(), price, actualCurrency
                );

                return new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel, actualCurrency);

            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    System.out.printf("✗ %s (CID: %d) - 실패: %s%n", entry.label(), entry.cid(), e.getMessage());
                    return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null, currency);
                }
                try { Thread.sleep(1000L * attempt); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
        return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null, currency);
    }

    private JsonNode fetchSecondaryDataJson(String hotelPageUrl, String currency) throws Exception {
        String pageUrl = addCurrencyToPageUrl(hotelPageUrl, currency);
        String cookieHeader = buildKoreanCookies();

        Document doc = Jsoup.connect(pageUrl)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")   // 추가된 헤더
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Cookie", cookieHeader)
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
        apiUrl = addCurrencyParameter(apiUrl, currency);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")   // 추가된 헤더
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Cookie", cookieHeader)
            .header("Referer", pageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return mapper.readTree(res.body());
    }

    private double extractPrice(JsonNode root) {
        double p = root.path("mosaicInitData").path("discount").path("cheapestPrice").asDouble(0);
        if (p == 0) p = root.path("stickyFooter").path("discount").path("cheapestPrice").asDouble(0);
        if (p == 0) {
            JsonNode sr = root.path("searchResult").path("searchResults");
            if (sr.isArray() && sr.size() > 0) {
                p = sr.get(0).path("pricing").path("price").asDouble(0);
            }
        }
        if (p == 0) {
            String txt = root.path("stickyFooter")
                             .path("discount")
                             .path("cheapestPriceWithCurrency")
                             .asText("");
            if (!txt.isEmpty()) {
                String num = txt.replaceAll("[^0-9.]", "");
                if (!num.isEmpty()) {
                    try { p = Double.parseDouble(num); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return p;
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

    private String addCurrencyToPageUrl(String url, String currency) {
        if (!url.contains("currencyCode=" + currency)) {
            url += url.contains("?") ? "&currencyCode=" : "?currencyCode=";
            url += currency;
        }
        if (url.contains("agoda.com/")) {
            url = url.replace("agoda.com/", "agoda.com/ko-kr/")
                     .replace("/ko-kr/ko-kr/", "/ko-kr/");
        }
        return url;
    }

    private String addCurrencyParameter(String apiUrl, String currency) {
        if (!apiUrl.contains("currencyCode=" + currency)) {
            apiUrl += apiUrl.contains("?") ? "&currencyCode=" : "?currencyCode=";
            apiUrl += currency;
        }
        return apiUrl;
    }

    private String buildKoreanCookies() {
        return String.join("; ",
            "selectedLanguage=ko-kr", "selectedCurrency=KRW", "country=KR", "locale=ko-KR",
            "language=ko-kr", "currency=KRW", "countrysite=KR",
            "userPreferredLanguage=ko-kr", "userPreferredCurrency=KRW",
            "AG_CURRENCY=KRW", "AG_LANGUAGE=ko-kr", "AG_COUNTRY=KR",
            "cookieConsent=1", "geolocation=KR", "timezone=Asia%2FSeoul", "deviceType=desktop"
        );
    }

    private List<CidEntry> buildCidList() {
        Random rnd = new Random();
        Set<Integer> randomFive = new LinkedHashSet<>();
        while (randomFive.size() < 5) {
            randomFive.add(rnd.nextInt(2000000 - 1800000 + 1) + 1800000);
        }
        List<CidEntry> list = new ArrayList<>(STATIC_CIDS);
        randomFive.forEach(cid -> list.add(new CidEntry("AUTO-" + cid, cid)));
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
        private final String currency;

        public LinkInfo(String label, int cid, String url,
                        double price, boolean soldOut, String hotel, String currency) {
            this.label = label;
            this.cid = cid;
            this.url = url;
            this.price = price;
            this.soldOut = soldOut;
            this.hotel = hotel;
            this.currency = currency;
        }

        public String getLabel()    { return label; }
        public int getCid()         { return cid; }
        public String getUrl()      { return url; }
        public double getPrice()    { return price; }
        public boolean isSoldOut()  { return soldOut; }
        public String getHotel()    { return hotel; }
        public String getCurrency() { return currency; }
    }
}
