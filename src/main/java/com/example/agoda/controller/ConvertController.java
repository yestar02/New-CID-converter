// src/main/java/com/example/agoda/controller/ConvertController.java
package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.net.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ConvertController {
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1", 1833982),
        new CidEntry("구글 지도 2", 1917614),
        new CidEntry("구글 지도 3", 1829668),
        new CidEntry("구글 검색 1", 1908617),
        new CidEntry("구글 검색 2", 1921868),
        new CidEntry("구글 검색 3", 1922847),
        new CidEntry("네이버",       1881505),
        new CidEntry("Bing",        1911217),
        new CidEntry("다음",        1908762),
        new CidEntry("DuckDuckGo",  1895204),
        new CidEntry("국민",        1563295),
        new CidEntry("우리",        1654104),
        new CidEntry("우리(마스터)",1932810),
		new CidEntry("현대",        1768446),
        new CidEntry("BC",         1748498),
        new CidEntry("신한",        1760133),
        new CidEntry("신한(마스터)",1917257),
        new CidEntry("토스",        1917334),
        new CidEntry("하나",        1729471),
        new CidEntry("카카오페이",   1845109),
        new CidEntry("마스터카드",   1889572),
        new CidEntry("유니온페이",   1801110),
        new CidEntry("비자",        1889319),
        new CidEntry("대한항공",     1904827),
        new CidEntry("아시아나항공", 1806212),
        new CidEntry("에어서울",     1800120)
    );
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민",         "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리",         "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리(마스터)","https://www.agoda.com/ko-kr/wooricardmaster"),
		new AffiliateLink("현대",         "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("BC",          "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한",         "https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("신한(마스터)", "https://www.agoda.com/ko-kr/shinhanmaster"),
        new AffiliateLink("토스",         "https://www.agoda.com/ko-kr/tossbank"),
        new AffiliateLink("하나",         "https://www.agoda.com/ko-kr/hanacard"),
        new AffiliateLink("카카오페이",   "https://www.agoda.com/ko-kr/kakaopay"),
        new AffiliateLink("마스터카드",   "https://www.agoda.com/ko-kr/krmastercard"),
        new AffiliateLink("유니온페이",   "https://www.agoda.com/ko-kr/unionpayKR"),
        new AffiliateLink("비자",         "https://www.agoda.com/ko-kr/visakorea"),
        new AffiliateLink("대한항공",     "https://www.agoda.com/ko-kr/koreanair"),
        new AffiliateLink("아시아나항공", "https://www.agoda.com/ko-kr/flyasiana"),
        new AffiliateLink("에어서울",     "https://www.agoda.com/ko-kr/airseoul")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Pattern apiUrlPattern = Pattern.compile("apiUrl\\s*=\\s*\"(.+?)\"");

    // 무작위 1800000~1999999 CID 50개 생성
    private List<CidEntry> buildCidList() {
        Set<Integer> rnd = new LinkedHashSet<>();
        Random r = new Random();
        while (rnd.size() < 50) {
            rnd.add(r.nextInt(2000000 - 1800000 + 1) + 1800000);
        }
        List<CidEntry> list = new ArrayList<>(STATIC_CIDS);
        rnd.forEach(cid -> list.add(new CidEntry("AUTO-" + cid, cid)));
        return list;
    }

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConvertResponse convert(@RequestBody Map<String,String> body) throws Exception {
        String url = body.get("url");
        if (url == null || !url.contains("agoda.com") || !url.contains("cid=") || url.contains("/search"))
            throw new IllegalArgumentException("유효하지 않은 URL입니다.");

        List<CidEntry> allCids = buildCidList();
        List<LinkInfo> results = new ArrayList<>();
        String hotelName = null;

        for (CidEntry entry : allCids) {
            String modUrl = url.replaceAll("cid=-?\\d+", "cid=" + entry.cid);
            // 1) HTML 파싱 → 내부 API URL 추출 (jsoup)[13]
            Document doc = Jsoup.connect(modUrl)
                                .header("Accept-Language","ko-KR")
                                .timeout(10_000)
                                .get();
            Element script = doc.selectFirst("script[data-selenium=script-initparam]");
            if (script == null) continue;
            Matcher m = apiUrlPattern.matcher(script.html());
            if (!m.find()) continue;
            String apiPath = m.group(1).replace("&amp;","&");

            // 2) JSON 호출
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.agoda.com" + apiPath))
                    .header("Accept-Language","ko-KR")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());

            JsonNode info = root.path("hotelInfo").path("name");
            JsonNode priceNode = root.path("rooms").get(0)
                                     .path("directPrice").path("originalPrice");
            if (priceNode.isNumber()) {
                double price = priceNode.asDouble();
                if (hotelName == null) hotelName = info.asText();
                results.add(new LinkInfo(entry.label, entry.cid, modUrl, price));
            }
            Thread.sleep(200);  // 과도 요청 방지
        }

        // 최저가
        results.sort(Comparator.comparingDouble(li -> li.price));
        LinkInfo cheapest = results.isEmpty() ? null : results.get(0);

        return new ConvertResponse(hotelName, results, cheapest, AFFILIATES);
    }

    // DTO 클래스들
    record CidEntry(String label, int cid) {}
    record LinkInfo(String label, int cid, String url, double price) {}
    record AffiliateLink(String label, String url) {}
    record ConvertResponse(
            String hotel,
            List<LinkInfo> priced,
            LinkInfo cheapest,
            List<AffiliateLink> affiliateLinks
    ) {}
}
