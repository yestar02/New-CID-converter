package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ConvertController {

    // 고정 CID 목록 (구글 2번 수정, 우리카드 마스터 다음에 현대카드 추가)
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 지도 1",    1833982),
        new CidEntry("구글 지도 2",    1917614),   // 변경된 값
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
        new CidEntry("현대카드",       1768446),    // 신규 항목
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

    // 제휴 바로가기 링크 (현대카드 추가)
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",       "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",       "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)","https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",       "https://www.agoda.com/ko-kr/hyundaicard"),  // 신규 링크
        new AffiliateLink("BC카드",         "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("신한카드",       "https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("신한카드(마스터)","https://www.agoda.com/ko-kr/shinhanmaster"),
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
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Pattern apiUrlPattern = Pattern.compile("apiUrl\\s*=\\s*\"(.+?)\"");

    /**
     * POST /api/convert
     * Request Body: { "url": "아고다 호텔 상세페이지 URL" }
     * Response: ConvertResponse
     */
    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConvertResponse convert(@RequestBody Map<String, String> body) throws Exception {
        String url = body.get("url");
        if (!isValidAgodaUrl(url)) {
            throw new IllegalArgumentException("유효하지 않은 아고다 URL입니다.");
        }

        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();
        String hotelName = null;

        for (CidEntry entry : cidList) {
            String modUrl = url.replaceAll("cid=-?\\d+", "cid=" + entry.cid);

            Document doc = Jsoup.connect(modUrl)
                                .header("Accept-Language", "ko-KR")
                                .timeout(10_000)
                                .get();
            Element scriptTag = doc.selectFirst("script[data-selenium=script-initparam]");
            if (scriptTag == null) continue;

            Matcher m = apiUrlPattern.matcher(scriptTag.html());
            if (!m.find()) continue;

            String apiPath = m.group(1).replace("&amp;", "&");
            HttpRequest apiReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.agoda.com" + apiPath))
                    .header("Accept-Language", "ko-KR")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> apiResp = http.send(apiReq, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(apiResp.body());

            JsonNode roomsNode = root.path("rooms");
            if (!roomsNode.isArray() || roomsNode.size() == 0) continue;

            JsonNode firstRoom = roomsNode.get(0);
            JsonNode priceNode = firstRoom.path("directPrice").path("originalPrice");
            if (!priceNode.isNumber()) continue;

            double price = priceNode.asDouble();
            if (hotelName == null) {
                JsonNode nameNode = root.path("hotelInfo").path("name");
                if (nameNode.isTextual()) {
                    hotelName = nameNode.asText();
                }
            }

            results.add(new LinkInfo(entry.label, entry.cid, modUrl, price));
            Thread.sleep(200);
        }

        results.sort(Comparator.comparingDouble(li -> li.price));
        LinkInfo cheapest = results.isEmpty() ? null : results.get(0);

        return new ConvertResponse(hotelName, results, cheapest, AFFILIATES);
    }

    private boolean isValidAgodaUrl(String url) {
        return url != null
            && url.contains("agoda.com")
            && url.contains("cid=")
            && !url.contains("/search");
    }

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

    // DTO & Record 선언
    public static record CidEntry(String label, int cid) {}
    public static record LinkInfo(String label, int cid, String url, double price) {}
    public static record AffiliateLink(String label, String url) {}
    public static record ConvertResponse(
        String hotel,
        List<LinkInfo> priced,
        LinkInfo cheapest,
        List<AffiliateLink> affiliateLinks
    ) {}
}
