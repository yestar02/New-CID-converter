package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ConvertController {

    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1", 1833982),
        new CidEntry("구글 지도 2", 1917614),
        new CidEntry("구글 지도 3", 1829668),
        // ... 나머지 CID 리스트 생략 없이 동일하게 유지
        new CidEntry("에어서울", 1800120)
    );

    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",       "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",       "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)","https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",       "https://www.agoda.com/ko-kr/hyundaicard"),
        // ... 나머지 제휴 링크 동일하게 유지
        new AffiliateLink("에어서울",       "https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        // URL 유효성 검사
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "주소를 입력해주세요."));
        } else if (!url.contains("agoda.com")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "아고다 주소가 아닌 것 같습니다."));
        } else if (url.contains("/search")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "검색 페이지 URL은 사용할 수 없습니다."));
        } else if (!url.contains("cid=")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "주소에서 cid 값을 찾을 수 없습니다."));
        }

        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();
        String hotelName = null;

        for (CidEntry entry : cidList) {
            String modUrl = url.replaceAll("cid=-?\\d+", "cid=" + entry.cid);
            try {
                JsonNode root = fetchSecondaryDataJson(modUrl);

                // 1) 호텔명 추출 (루트 바로 아래 hotelInfo.name)
                if (hotelName == null) {
                    JsonNode hn = root.path("hotelInfo").path("name");
                    hotelName = hn.isTextual() ? hn.asText() : "호텔명 없음";
                }

                // 2) 가격 추출 (inquiryProperty.cheapestPrice), 콤마 제거 후 파싱
                JsonNode pp = root.path("inquiryProperty").path("cheapestPrice");
                String txt = pp.isTextual() ? pp.asText().replaceAll(",", "") : "0";
                double price = txt.isEmpty() ? 0 : Double.parseDouble(txt);
                boolean isSoldOut = price == 0;

                results.add(new LinkInfo(entry.label, entry.cid, modUrl, price, isSoldOut));
                Thread.sleep(200);
            } catch (Exception e) {
                if (hotelName == null) {
                    hotelName = "호텔명 추출 중 오류: " + e.getMessage();
                }
            }
        }

        // 예약 가능한 객실만 최저가 계산
        List<LinkInfo> available = results.stream()
            .filter(r -> !r.isSoldOut && r.price > 0)
            .sorted(Comparator.comparingDouble(LinkInfo::price))
            .toList();
        LinkInfo cheapest = available.isEmpty() ? null : available.get(0);

        Map<String,Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("hotel", hotelName);
        resp.put("priced", results);
        resp.put("cheapest", cheapest);
        resp.put("affiliateLinks", AFFILIATES);
        return ResponseEntity.ok(resp);
    }

    /**
     * DevTools에서 확인된 BelowFoldParams/GetSecondaryData API URL을
     * script-initparam에서 추출된 apiUrl 그대로 호출합니다.
     */
    private JsonNode fetchSecondaryDataJson(String hotelPageUrl) throws Exception {
        // 1) HTML에서 script-initparam으로 API URL 추출
        Document doc = Jsoup.connect(hotelPageUrl)
            .header("Accept-Language","ko-KR")
            .timeout((int)Duration.ofSeconds(10).toMillis())
            .get();
        Element s = doc.selectFirst("script[data-selenium=script-initparam]");
        String content = s != null
            ? (s.data().isEmpty() ? s.text() : s.data())
            : "";
        // apiUrl 파싱 (HTML 인코딩된 &amp; 치환 포함)
        String relative = content.split("apiUrl\\s*=\\s*\"")[1]
            .split("\"")[0].replace("&amp;","&");
        String apiUrl = "https://www.agoda.com" + relative;

        // 2) 추출된 API URL 그대로 호출 (추가 파라미터 수동 추가 금지)
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept-Language","ko-KR")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(r.body());
    }

    private List<CidEntry> buildCidList() {
        Set<Integer> rnd = new LinkedHashSet<>();
        Random r = new Random();
        while (rnd.size() < 50) {
            rnd.add(r.nextInt(2000000-1800000+1)+1800000);
        }
        List<CidEntry> list = new ArrayList<>(STATIC_CIDS);
        rnd.forEach(cid -> list.add(new CidEntry("AUTO-"+cid, cid)));
        return list;
    }

    // DTO & Record
    public static record CidEntry(String label, int cid) {}
    public static record LinkInfo(String label, int cid, String url, double price, boolean isSoldOut) {}
    public static record AffiliateLink(String label, String url) {}
}
