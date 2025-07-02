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

    // static 제거, final로 초기화
    private final ObjectMapper mapper = new ObjectMapper();

    // 접근 지시자를 public으로 명시
    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        try {
            // null 체크 강화
            if (body == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "요청 본문이 없습니다."));
            }

            String url = body.get("url");
            if (url == null || url.trim().isEmpty()) {
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
                try {
                    fetchForCid(url, entry, results);
                } catch (Exception e) {
                    System.err.println("CID " + entry.cid() + " 처리 중 오류: " + e.getMessage());
                    results.add(new LinkInfo(entry.label(), entry.cid(), 
                        url.replaceAll("cid=-?\\d+", "cid=" + entry.cid()), 0, true, null));
                }
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
                "affiliateLinks", getAffiliateLinks()
            ));

        } catch (Exception e) {
            System.err.println("전체 처리 중 오류: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "서버 내부 오류가 발생했습니다."));
        }
    }

    // private에서 public으로 변경하고 더 안전하게 처리
    public void fetchForCid(String baseUrl, CidEntry entry, List<LinkInfo> results) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .build();
                
            String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
            
            // Jsoup으로 HTML 로드
            Document doc = Jsoup.connect(modUrl)
                .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get();

            Element script = doc.selectFirst("script[data-selenium=script-initparam]");
            if (script == null) {
                throw new RuntimeException("script-initparam 태그를 찾을 수 없음");
            }

            String content = Optional.ofNullable(script.data())
                .filter(data -> !data.isEmpty())
                .orElse(script.text());
                
            if (content == null || content.trim().isEmpty()) {
                throw new RuntimeException("script 내용이 비어있음");
            }

            // apiUrl 추출
            int startIndex = content.indexOf("apiUrl = \"");
            if (startIndex == -1) {
                throw new RuntimeException("apiUrl 패턴을 찾을 수 없음");
            }
            
            startIndex += "apiUrl = \"".length();
            int endIndex = content.indexOf("\"", startIndex);
            if (endIndex == -1) {
                throw new RuntimeException("apiUrl 끝을 찾을 수 없음");
            }
            
            String apiPath = content.substring(startIndex, endIndex).replace("&amp;", "&");
            
            if (!apiPath.contains("currencyCode=")) apiPath += "&currencyCode=KRW";
            if (!apiPath.contains("price_view="))   apiPath += "&price_view=2";

            String apiUrl = "https://www.agoda.com" + apiPath;

            // API 호출
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept-Language","ko-KR,ko;q=0.9,en;q=0.8")
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", modUrl)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            
            JsonNode root = mapper.readTree(response.body());

            // 가격 및 호텔명 추출
            String hotel = Optional.ofNullable(root.path("hotelInfo").path("name"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .orElse(null);

            double price = root.path("mosaicInitData").path("discount").path("cheapestPrice").asDouble(0);
            
            if (price == 0) {
                String raw = root.path("stickyFooter").path("discount").path("cheapestPriceWithCurrency").asText("");
                String numeric = raw.replaceAll("[^0-9]", "");
                if (!numeric.isEmpty()) {
                    price = Double.parseDouble(numeric);
                }
            }

            boolean soldOut = price == 0;
            results.add(new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel));

        } catch (Exception e) {
            System.err.println("fetchForCid 오류 - CID: " + entry.cid() + ", 메시지: " + e.getMessage());
            results.add(new LinkInfo(entry.label(), entry.cid(), 
                baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid()), 0, true, null));
        }
    }

    // 나머지 메서드들...
    public List<CidEntry> buildCidList() {
        // 기존 로직 유지
        return new ArrayList<>(); // 임시로 빈 리스트 반환
    }
    
    public List<AffiliateLink> getAffiliateLinks() {
        // 기존 AFFILIATES 상수 반환
        return new ArrayList<>(); // 임시로 빈 리스트 반환
    }

    public static record CidEntry(String label, int cid) {}
    public static record LinkInfo(String label, int cid, String url, double price, boolean soldOut, String hotel) {
        public String getLabel() { return label; }
        public int getCid() { return cid; }
        public String getUrl() { return url; }
        public double getPrice() { return price; }
        public boolean isSoldOut() { return soldOut; }
        public String getHotel() { return hotel; }
    }
    public static record AffiliateLink(String label, String url) {}
}
