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

    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",       "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",       "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)", "https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",       "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("BC카드",         "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한카드",       "https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("신한카드(마스터)", "https://www.agoda.com/ko-kr/shinhanmaster"),
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

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        if (body == null || body.get("url") == null || body.get("url").isBlank()) {
            return bad("유효한 URL을 입력해주세요.");
        }
        String url = body.get("url");
        if (!url.contains("agoda.com") || !url.contains("cid=")) {
            return bad("아고다 호텔 상세 URL이 아닙니다.");
        }

        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();
        
        // 첫 번째 CID만 테스트해서 디버깅
        CidEntry testEntry = cidList.get(0);
        System.out.println("=== 디버깅: " + testEntry.label() + " (CID: " + testEntry.cid() + ") ===");
        fetchWithRetry(url, testEntry, results);
        
        for (int i = 1; i < Math.min(3, cidList.size()); i++) {
            fetchWithRetry(url, cidList.get(i), results);
        }

        String hotelName = results.stream()
            .map(LinkInfo::hotel)
            .filter(Objects::nonNull)
            .findFirst().orElse("호텔명 없음");

        LinkInfo cheapest = results.stream()
            .filter(r -> !r.soldOut() && r.price() > 0)
            .min(Comparator.comparingDouble(LinkInfo::price))
            .orElse(null);

        Map<String,Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("hotel", hotelName);
        resp.put("priced", results);
        resp.put("cheapest", cheapest);
        resp.put("affiliateLinks", AFFILIATES);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String,Object>> bad(String msg) {
        Map<String,Object> e = Map.of("success", false, "message", msg);
        return ResponseEntity.badRequest().body(new HashMap<>(e));
    }

    private void fetchWithRetry(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        System.out.println("수정된 URL: " + modUrl);
        
        int attempts = 3;
        while (attempts-- > 0) {
            try {
                JsonNode root = fetchSecondaryDataJson(modUrl);
                
                // JSON 구조 디버깅
                System.out.println("API 응답 받음. 루트 필드들: " + root.fieldNames());
                
                // 호텔명 추출 시도
                JsonNode hotelInfoNode = root.path("hotelInfo");
                System.out.println("hotelInfo 존재: " + !hotelInfoNode.isMissingNode());
                if (!hotelInfoNode.isMissingNode()) {
                    System.out.println("hotelInfo 필드들: " + hotelInfoNode.fieldNames());
                    JsonNode nameNode = hotelInfoNode.path("name");
                    System.out.println("name 노드: " + nameNode.toString());
                }
                
                String hotel = root.path("hotelInfo").path("name").asText(null);
                System.out.println("추출된 호텔명: " + hotel);
                
                // 가격 추출 시도
                JsonNode mosaicNode = root.path("mosaicInitData");
                System.out.println("mosaicInitData 존재: " + !mosaicNode.isMissingNode());
                if (!mosaicNode.isMissingNode()) {
                    JsonNode discountNode = mosaicNode.path("discount");
                    System.out.println("discount 존재: " + !discountNode.isMissingNode());
                    if (!discountNode.isMissingNode()) {
                        System.out.println("discount 필드들: " + discountNode.fieldNames());
                        JsonNode priceNode = discountNode.path("cheapestPrice");
                        System.out.println("cheapestPrice 노드: " + priceNode.toString());
                    }
                }
                
                double price = root.path("mosaicInitData")
                                   .path("discount")
                                   .path("cheapestPrice")
                                   .asDouble(0);
                System.out.println("추출된 가격: " + price);
                
                // 다른 경로들도 시도
                double stickyPrice = root.path("stickyFooter")
                                        .path("discount")
                                        .path("cheapestPrice")
                                        .asDouble(0);
                System.out.println("stickyFooter에서 가격: " + stickyPrice);
                
                // inquiryProperty도 시도
                double inquiryPrice = root.path("inquiryProperty")
                                          .path("cheapestPrice")
                                          .asDouble(0);
                System.out.println("inquiryProperty에서 가격: " + inquiryPrice);
                
                if (price == 0 && stickyPrice > 0) price = stickyPrice;
                if (price == 0 && inquiryPrice > 0) price = inquiryPrice;
                
                boolean soldOut = price == 0;
                results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel));
                return;
                
            } catch (Exception e) {
                System.err.println("CID " + entry.cid() + " 오류: " + e.getMessage());
                e.printStackTrace();
                if (attempts == 0) {
                    results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null));
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private JsonNode fetchSecondaryDataJson(String hotelPageUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
            .build();

        System.out.println("HTML 페이지 로드 중: " + hotelPageUrl);
        Document doc = Jsoup.connect(hotelPageUrl)
            .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent","Mozilla/5.0")
            .timeout(15000)
            .get();

        // script 태그들 모두 확인
        Elements scripts = doc.select("script");
        System.out.println("총 script 태그 수: " + scripts.size());
        
        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        if (script == null) {
            // 다른 가능한 선택자들 시도
            script = doc.selectFirst("script[data-selenium=script-initParam]");
            if (script == null) {
                script = doc.selectFirst("script:contains(apiUrl)");
                if (script == null) {
                    System.err.println("apiUrl을 포함한 script 태그를 찾을 수 없음");
                    throw new IllegalStateException("script-initparam 태그를 찾을 수 없습니다");
                }
            }
        }
        
        System.out.println("script 태그 찾음: " + script.attr("data-selenium"));
        String content = script.data().isEmpty() ? script.text() : script.data();
        System.out.println("script 내용 길이: " + content.length());
        
        if (content.contains("apiUrl")) {
            System.out.println("apiUrl 패턴 발견");
        } else {
            System.err.println("apiUrl 패턴이 script에 없음");
            throw new IllegalStateException("apiUrl 패턴을 찾을 수 없습니다");
        }

        int s = content.indexOf("apiUrl = \"");
        if (s < 0) {
            // 다른 패턴들 시도
            s = content.indexOf("apiUrl\":\"");
            if (s < 0) {
                s = content.indexOf("\"apiUrl\":\"");
                if (s >= 0) s += 1; // 앞의 따옴표 건너뛰기
            }
        }
        
        if (s < 0) {
            System.err.println("apiUrl 패턴을 찾을 수 없음. content 일부: " + content.substring(0, Math.min(500, content.length())));
            throw new IllegalStateException("apiUrl 패턴을 찾을 수 없습니다");
        }
        
        String marker = content.substring(s, s + 10);
        System.out.println("발견된 패턴: " + marker);
        
        if (marker.startsWith("apiUrl = \"")) {
            s += "apiUrl = \"".length();
        } else if (marker.startsWith("apiUrl\":\"")) {
            s += "apiUrl\":\"".length();
        }
        
        int e = content.indexOf("\"", s);
        if (e < 0) {
            System.err.println("apiUrl 끝 따옴표를 찾을 수 없음");
            throw new IllegalStateException("apiUrl 끝 따옴표를 찾을 수 없습니다");
        }

        String apiPath = content.substring(s, e).replace("&amp;", "&");
        System.out.println("추출된 apiPath: " + apiPath);
        
        if (!apiPath.contains("currencyCode=")) apiPath += "&currencyCode=KRW";
        if (!apiPath.contains("price_view="))   apiPath += "&price_view=2";

        String apiUrl = "https://www.agoda.com" + apiPath;
        System.out.println("최종 API URL: " + apiUrl);
        
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
            .header("User-Agent","Mozilla/5.0")
            .header("Referer", hotelPageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
            
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println("API 응답 상태: " + res.statusCode());
        System.out.println("API 응답 길이: " + res.body().length());
        
        return mapper.readTree(res.body());
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
    public static record LinkInfo(String label, int cid, String url, double price, boolean soldOut, String hotel) {}
    public static record AffiliateLink(String label, String url) {}
}
