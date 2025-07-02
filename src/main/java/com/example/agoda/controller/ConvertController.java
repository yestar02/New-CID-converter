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
        new CidEntry("구글 지도 1",     1833982),
        new CidEntry("구글 지도 2",     1917614),
        new CidEntry("구글 지도 3",     1829668),
        new CidEntry("구글 검색 1",     1908617),
        new CidEntry("구글 검색 2",     1921868),
        new CidEntry("구글 검색 3",     1922847),
        new CidEntry("네이버",          1881505),
        new CidEntry("Bing",           1911217),
        new CidEntry("다음",           1908762),
        new CidEntry("DuckDuckGo",     1895204),
        new CidEntry("국민카드",        1563295),
        new CidEntry("우리카드",        1654104),
        new CidEntry("우리카드(마스터)",1932810),
        new CidEntry("현대카드",        1768446),
        new CidEntry("BC카드",          1748498),
        new CidEntry("신한카드",        1760133),
        new CidEntry("신한카드(마스터)",1917257),
        new CidEntry("토스",            1917334),
        new CidEntry("하나카드",        1729471),
        new CidEntry("카카오페이",      1845109),
        new CidEntry("마스터카드",      1889572),
        new CidEntry("유니온페이",      1801110),
        new CidEntry("비자",            1889319),
        new CidEntry("대한항공(적립)",  1904827),
        new CidEntry("아시아나항공(적립)",1806212),
        new CidEntry("에어서울",        1800120)
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

    // 쿠키 무시용 HttpClient (세션 격리)
    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
            .build();
    }

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

        List<CidEntry> cidList = buildCidList();  // 무작위 CID 5개 추가
        List<LinkInfo> results = new ArrayList<>();
        for (CidEntry entry : cidList) {
            fetchForCid(url, entry, results);
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

        return ResponseEntity.ok(Map.of(
            "success", true,
            "hotel", hotelName,
            "priced", results,
            "cheapest", cheapest,
            "affiliateLinks", AFFILIATES
        ));
    }

    private void fetchForCid(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        HttpClient client = newHttpClient();  // 세션별 HttpClient
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());

        try {
            // 1) 페이지 HTML 로드 → script-initparam 재파싱
            Document doc = Jsoup.connect(modUrl)
                .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
                .header("User-Agent","Mozilla/5.0")
                .timeout((int)Duration.ofSeconds(15).toMillis())
                .get();

            Element script = doc.selectFirst("script[data-selenium=script-initparam]");
            if (script == null) throw new IllegalStateException("script-initparam 없음");
            String content = script.data().isEmpty() ? script.text() : script.data();
            String[] parts = content.split("apiUrl\\s*=\\s*\"");
            if (parts.length < 2) throw new IllegalStateException("apiUrl 패턴 없음");
            String apiPath = parts[1].split("\"")[0].replace("&amp;", "&");

            // KRW, price_view=2 보장
            if (!apiPath.contains("currencyCode=")) apiPath += "&currencyCode=KRW";
            if (!apiPath.contains("price_view="))   apiPath += "&price_view=2";

            String apiUrl = "https://www.agoda.com" + apiPath;

            // 2) API 호출
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
                .header("User-Agent","Mozilla/5.0")
                .header("Referer", modUrl)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

            HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = mapper.readTree(resp.body());

            // 3) 가격 및 호텔명 추출
            String hotel = root.path("hotelInfo").path("name").asText(null);
            double price = root.path("mosaicInitData")
                               .path("discount")
                               .path("cheapestPrice")
                               .asDouble(0);
            if (price == 0) {
                String raw = root.path("stickyFooter")
                                 .path("discount")
                                 .path("cheapestPriceWithCurrency")
                                 .asText("");
                String num = raw.replaceAll("[^0-9]", "");
                price = num.isBlank() ? 0 : Double.parseDouble(num);
            }
            boolean soldOut = price == 0;
            results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel));

        } catch (Exception e) {
            results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null));
        }
    }

    private List<CidEntry> buildCidList() {
        Set<Integer> rnd = new LinkedHashSet<>();
        Random r = new Random();
        while (rnd.size() < 5) {
            rnd.add(r.nextInt(2000000 - 1800000 + 1) + 1800000);
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
        public String getLabel()   { return label; }
        public int getCid()        { return cid; }
        public String getUrl()     { return url; }
        public double getPrice()   { return price; }
        public boolean isSoldOut() { return soldOut; }
        public String getHotel()   { return hotel; }
    }

    public static record AffiliateLink(String label, String url) {}
}
