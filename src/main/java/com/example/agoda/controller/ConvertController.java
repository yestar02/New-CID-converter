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

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
            .build();
    }

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        try {
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
        } catch (Exception e) {
            System.err.println("Convert 메서드 오류: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "서버 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    private void fetchForCid(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        HttpClient client = null;
        try {
            client = newHttpClient();
            String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
            
            // 1) HTML 로드
            Document doc = Jsoup.connect(modUrl)
                .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
                .header("User-Agent","Mozilla/5.0")
                .timeout((int)Duration.ofSeconds(15).toMillis())
                .get();

            if (doc == null) {
                throw new IllegalStateException("HTML 로드 실패");
            }

            // 2) script 태그 찾기
            Element script = doc.selectFirst("script[data-selenium=script-initparam]");
            if (script == null) {
                throw new IllegalStateException("script-initparam 태그 없음");
            }

            // 3) script 내용 추출
            String scriptData = script.data();
            String scriptText = script.text();
            String content = (scriptData != null && !scriptData.isEmpty()) ? scriptData : scriptText;
            
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("script 내용이 비어있음");
            }

            // 4) apiUrl 추출
            String[] parts = content.split("apiUrl\\s*=\\s*\"");
            if (parts == null || parts.length < 2) {
                throw new IllegalStateException("apiUrl 패턴을 찾을 수 없음");
            }

            String secondPart = parts[1];
            if (secondPart == null) {
                throw new IllegalStateException("apiUrl 값 부분이 null");
            }

            String[] urlParts = secondPart.split("\"");
            if (urlParts == null || urlParts.length < 1) {
                throw new IllegalStateException("apiUrl 값을 추출할 수 없음");
            }

            String apiPath = urlParts[0];
            if (apiPath == null) {
                throw new IllegalStateException("apiPath가 null");
            }

            apiPath = apiPath.replace("&amp;", "&");

            // 5) KRW, price_view 파라미터 보장
            if (!apiPath.contains("currencyCode=")) apiPath += "&currencyCode=KRW";
            if (!apiPath.contains("price_view="))   apiPath += "&price_view=2";

            String apiUrl = "https://www.agoda.com" + apiPath;

            // 6) API 호출
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
            
            if (resp == null || resp.body() == null) {
                throw new IllegalStateException("API 응답이 null");
            }

            JsonNode root = mapper.readTree(resp.body());
            if (root == null) {
                throw new IllegalStateException("JSON 파싱 결과가 null");
            }

            // 7) 가격 및 호텔명 추출
            String hotel = null;
            JsonNode hotelNode = root.path("hotelInfo").path("name");
            if (hotelNode != null && hotelNode.isTextual()) {
                hotel = hotelNode.asText();
            }

            double price = 0;
            JsonNode priceNode = root.path("mosaicInitData").path("discount").path("cheapestPrice");
            if (priceNode != null && priceNode.isNumber()) {
                price = priceNode.asDouble(0);
            }

            if (price == 0) {
                JsonNode stickyNode = root.path("stickyFooter").path("discount").path("cheapestPriceWithCurrency");
                if (stickyNode != null && stickyNode.isTextual()) {
                    String raw = stickyNode.asText();
                    if (raw != null) {
                        String num = raw.replaceAll("[^0-9]", "");
                        if (num != null && !num.isBlank()) {
                            price = Double.parseDouble(num);
                        }
                    }
                }
            }

            boolean soldOut = price == 0;
            results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel));

        } catch (Exception e) {
            System.err.println("fetchForCid 오류 - CID: " + entry.cid() + ", 오류: " + e.getMessage());
            results.add(new LinkInfo(entry.label(), entry.cid(), 
                baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid()), 0, true, null));
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
