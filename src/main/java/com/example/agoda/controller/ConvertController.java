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

    // 고정 CID 목록 (구글2번/현대카드 반영)
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

    // 제휴 바로가기 링크
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("국민카드",       "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("우리카드",       "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("우리카드(마스터)","https://www.agoda.com/ko-kr/wooricardmaster"),
        new AffiliateLink("현대카드",       "https://www.agoda.com/ko-kr/hyundaicard"),
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

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        
        // 유효성 검사 (파이썬 스타일)
        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "주소를 입력해주세요."));
        } else if (!url.contains("agoda.com")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "아고다 주소가 아닌 것 같아요."));
        } else if (url.contains("agoda.com/ko-kr/search")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "검색 페이지 URL은 사용할 수 없어요."));
        } else if (!url.contains("cid=")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "주소에서 cid 값을 찾을 수 없어요. \n올바른 주소인지 확인해주세요."));
        }

        List<CidEntry> cidList = buildCidList();
        List<LinkInfo> results = new ArrayList<>();
        String hotelName = null;

        for (CidEntry entry : cidList) {
            String modUrl = url.replaceAll("cid=-?\\d+", "cid=" + entry.cid);

            try {
                // 호텔 정보 및 가격 추출
                PriceInfo priceInfo = extractPriceInfo(modUrl);
                
                if (hotelName == null) {
                    hotelName = priceInfo.hotelName;
                }

                // 가격이 있거나 매진 상태인 경우 결과에 추가
                if (priceInfo.price > 0 || priceInfo.isSoldOut) {
                    results.add(new LinkInfo(
                        entry.label, 
                        entry.cid, 
                        modUrl, 
                        priceInfo.price,
                        priceInfo.isSoldOut
                    ));
                }

                Thread.sleep(200);
            } catch (Exception e) {
                if (hotelName == null) {
                    hotelName = "호텔 이름을 찾는 중 오류 발생: " + e.getMessage();
                }
            }
        }

        // 매진이 아닌 것들 중에서 최저가 계산
        List<LinkInfo> availableRooms = results.stream()
                .filter(r -> !r.isSoldOut && r.price > 0)
                .sorted(Comparator.comparingDouble(li -> li.price))
                .toList();
        
        LinkInfo cheapest = availableRooms.isEmpty() ? null : availableRooms.get(0);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "hotel", hotelName != null ? hotelName : "호텔 정보 없음",
            "priced", results,
            "cheapest", cheapest,
            "affiliateLinks", AFFILIATES
        ));
    }

    private PriceInfo extractPriceInfo(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .header("Accept-Language", "ko-KR")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get();

        String hotelName = "호텔명 없음";
        double price = 0;
        boolean isSoldOut = false;

        // 1. 매진 메시지 체크
        Element soldOutElement = doc.selectFirst(".RoomGrid-searchTimeOutText");
        if (soldOutElement != null && soldOutElement.text().contains("고객님이 선택한 날짜에 이 숙소의 본 사이트 잔여 객실이 없습니다")) {
            isSoldOut = true;
        }

        // 2. API 호출 방식으로 호텔명과 가격 추출
        Element scriptTag = doc.selectFirst("script[data-selenium=script-initparam]");
        if (scriptTag != null) {
            String scriptContent = scriptTag.data().isEmpty() ? scriptTag.text() : scriptTag.data();
            Matcher m = apiUrlPattern.matcher(scriptContent);
            if (m.find()) {
                String apiPath = m.group(1).replace("&amp;", "&");
                HttpRequest apiReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://www.agoda.com" + apiPath))
                        .header("Accept-Language", "ko-KR")
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                HttpResponse<String> apiResp = http.send(apiReq, HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(apiResp.body());

                // 호텔명 추출
                JsonNode nameNode = root.path("hotelInfo").path("name");
                if (nameNode.isTextual()) {
                    hotelName = nameNode.asText();
                }

                // 가격 추출 (매진이 아닌 경우에만)
                if (!isSoldOut) {
                    JsonNode roomsNode = root.path("rooms");
                    if (roomsNode.isArray() && roomsNode.size() > 0) {
                        JsonNode firstRoom = roomsNode.get(0);
                        JsonNode priceNode = firstRoom.path("directPrice").path("originalPrice");
                        if (priceNode.isNumber()) {
                            price = priceNode.asDouble();
                        }
                    }
                    
                    // soldOutRooms에서 참고 가격 추출
                    if (price == 0) {
                        JsonNode soldOutRooms = root.path("soldOutRooms");
                        if (soldOutRooms.isArray() && soldOutRooms.size() > 0) {
                            for (JsonNode soldOutRoom : soldOutRooms) {
                                JsonNode exclusivePrice = soldOutRoom.path("exclusivePrice");
                                JsonNode allInclusivePrice = soldOutRoom.path("allInclusivePrice");
                                
                                if (exclusivePrice.isNumber() && exclusivePrice.asDouble() > 0) {
                                    price = exclusivePrice.asDouble();
                                    break;
                                } else if (allInclusivePrice.isNumber() && allInclusivePrice.asDouble() > 0) {
                                    price = allInclusivePrice.asDouble();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 호텔명이 아직 없으면 HTML에서 추출
        if ("호텔명 없음".equals(hotelName)) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null && !h1.text().isEmpty()) {
                hotelName = h1.text();
            } else {
                Element title = doc.selectFirst("title");
                if (title != null && !title.text().isEmpty()) {
                    hotelName = title.text().split("\\|")[0].trim();
                }
            }
        }

        return new PriceInfo(hotelName, price, isSoldOut);
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

    // DTO 클래스들
    public static record CidEntry(String label, int cid) {}
    public static record LinkInfo(String label, int cid, String url, double price, boolean isSoldOut) {}
    public static record AffiliateLink(String label, String url) {}
    
    private static class PriceInfo {
        final String hotelName;
        final double price;
        final boolean isSoldOut;
        
        PriceInfo(String hotelName, double price, boolean isSoldOut) {
            this.hotelName = hotelName;
            this.price = price;
            this.isSoldOut = isSoldOut;
        }
    }
}
