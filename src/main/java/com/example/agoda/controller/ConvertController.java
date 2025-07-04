package com.example.agoda.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ConvertController {

    // 고정 CID 목록 (업데이트된 리스트)
    private static final List<CidEntry> STATIC_CIDS = List.of(
        new CidEntry("구글 1", 1833982),
        new CidEntry("구글 2", 1917614),
        new CidEntry("구글 3", 1833981),
        new CidEntry("구글 4", 1908617),
        new CidEntry("구글 5", 1921868),
        new CidEntry("구글 6", 1922847),
        new CidEntry("네이버(참고용)", 1891504),
        new CidEntry("국민카드", 1563295),
        new CidEntry("삼성카드", 1783115),
        new CidEntry("신한카드", 1760133),
        new CidEntry("우리카드", 1654104),
        new CidEntry("현대카드", 1641446),
        new CidEntry("농협카드", 1827579),
        new CidEntry("BC카드", 1748498),
        new CidEntry("토스카드", 1917334),
        new CidEntry("하나카드", 1729471),
        new CidEntry("트레블월렛", 1917349),
        new CidEntry("카카오페이", 1845109),
        new CidEntry("페이코(PAYCO)", 1845157),
        new CidEntry("VISA 카드", 1889319),
        new CidEntry("마스터카드", 1889572),
        new CidEntry("유니온페이", 1937708),
        new CidEntry("대한항공(적립)", 1904827)
    );

    // 제휴 링크 목록 (업데이트된 리스트)
    private static final List<AffiliateLink> AFFILIATES = List.of(
        new AffiliateLink("네이버", "https://www.agoda.com/ko-kr/?cid=1891504"),
        new AffiliateLink("국민카드", "https://www.agoda.com/ko-kr/kbcard"),
        new AffiliateLink("삼성카드", "https://www.agoda.com/ko-kr/samsungcardevent"),
        new AffiliateLink("신한카드", "https://www.agoda.com/ko-kr/shinhancard"),
        new AffiliateLink("우리카드", "https://www.agoda.com/ko-kr/wooricard"),
        new AffiliateLink("현대카드", "https://www.agoda.com/ko-kr/hyundaicard"),
        new AffiliateLink("농협카드", "https://www.agoda.com/ko-kr/nhcard"),
        new AffiliateLink("BC카드", "https://www.agoda.com/ko-kr/bccard"),
        new AffiliateLink("토스카드", "https://www.agoda.com/ko-kr/tossbank"),
        new AffiliateLink("하나카드", "https://www.agoda.com/ko-kr/hanacard"),
        new AffiliateLink("트레블월렛", "https://www.agoda.com/ko-kr/travelwallet"),
        new AffiliateLink("카카오페이", "https://www.agoda.com/ko-kr/kakaopay"),
        new AffiliateLink("페이코(PAYCO)", "https://www.agoda.com/ko-kr/payco"),
        new AffiliateLink("VISA 카드", "https://www.agoda.com/ko-kr/visakorea"),
        new AffiliateLink("마스터카드", "https://www.agoda.com/ko-kr/krmastercard"),
        new AffiliateLink("유니온페이", "https://www.agoda.com/ko-kr/unionpayKR"),
        new AffiliateLink("대한항공(적립)", "https://www.agoda.com/ko-kr/koreanair")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    // SSE 관리를 위한 맵
    private final Map<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    // SSE 연결 엔드포인트
    @GetMapping(value = "/progress/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5분 타임아웃
        sseEmitters.put(sessionId, emitter);

        emitter.onCompletion(() -> sseEmitters.remove(sessionId));
        emitter.onTimeout(() -> sseEmitters.remove(sessionId));
        emitter.onError((ex) -> sseEmitters.remove(sessionId));

        return emitter;
    }

    @PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> convert(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        String sessionId = body.get("sessionId");

        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "주소를 입력해주세요."));
        }
        if (!url.contains("agoda.com") || !url.contains("cid=")) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "유효한 아고다 상세 URL을 입력해주세요."));
        }

        // 비동기로 처리
        executorService.submit(() -> processConversion(url, sessionId));

        return ResponseEntity.ok(Map.of("success", true, "message", "처리를 시작했습니다."));
    }

    private void processConversion(String url, String sessionId) {
        try {
            String currency = extractCurrencyFromUrl(url);
            List<CidEntry> cidList = buildCidList();
            int totalSteps = cidList.size() + 2;
            int currentStep = 0;

            // 1) 프로그램 세션으로 한 번 접속하여 쿠키 수집 및 수정
            sendProgress(sessionId, ++currentStep, totalSteps);
            Map<String, String> sessionCookies = collectSessionCookies(url);

            // 2) 세션 쿠키로 초기 호텔명과 가격 가져오기
            sendProgress(sessionId, ++currentStep, totalSteps);
            String initialHotel = "호텔명 없음";
            double initialPrice = 0;
            String initialCurrency = "UNKNOWN";

            try {
                JsonNode initialRoot = fetchSecondaryDataJsonWithSession(url, currency, "INITIAL", sessionCookies);
                initialHotel = initialRoot.path("hotelInfo").path("name").asText("호텔명 없음");
                initialPrice = initialRoot.path("mosaicInitData").path("discount").path("cheapestPrice").asDouble(0);
                initialCurrency = initialRoot.path("mosaicInitData").path("discount").path("currency").asText("UNKNOWN");
            } catch (Exception e) {
                System.out.println("초기 가격 정보를 가져오는데 실패했습니다: " + e.getMessage());
            }

            // 3) 병렬 CID별 가격 수집
            List<LinkInfo> results = new ArrayList<>();
            ExecutorService cidExecutor = Executors.newFixedThreadPool(8);
            AtomicInteger completedCount = new AtomicInteger(0);
            
            // currentStep을 final로 만들기 위해 별도 변수 사용
            final int finalCurrentStep = currentStep;

            try {
                List<CompletableFuture<LinkInfo>> futures = cidList.stream()
                    .map(entry -> CompletableFuture.supplyAsync(() -> {
                        try {
                            LinkInfo result = fetchSequentiallyWithSession(url, entry, sessionCookies);

                            // 진행율 업데이트 (동기화) - finalCurrentStep 사용
                            int completed = completedCount.incrementAndGet();
                            sendProgress(sessionId, finalCurrentStep + completed, totalSteps);

                            return result;
                        } catch (Exception e) {
                            System.out.printf("✗ %s (CID: %d) - 병렬 처리 실패: %s%n",
                                entry.label(), entry.cid(), e.getMessage());
                            return new LinkInfo(entry.label(), entry.cid(),
                                url.replaceAll("cid=-?\\d+", "cid=" + entry.cid()), 0, true, null);
                        }
                    }, cidExecutor))
                    .collect(Collectors.toList());

                // 모든 작업 완료 대기
                List<LinkInfo> unsortedResults = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

                // *** 고정 CID 리스트 순서대로 정렬 ***
                results.addAll(sortResultsByFixedOrder(unsortedResults, cidList));

            } finally {
                cidExecutor.shutdown();
                try {
                    if (!cidExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                        cidExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    cidExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            String hotelName = results.stream()
                .map(LinkInfo::getHotel)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(initialHotel);

            LinkInfo cheapest = results.stream()
                .filter(r -> !r.isSoldOut() && r.getPrice() > 0)
                .min(Comparator.comparingDouble(LinkInfo::getPrice))
                .orElse(null);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("hotel", hotelName);
            resp.put("initialPrice", initialPrice);
            resp.put("initialCurrency", initialCurrency);
            resp.put("priced", results);
            resp.put("cheapest", cheapest);
            resp.put("affiliateLinks", AFFILIATES);
            resp.put("totalCids", cidList.size());
            resp.put("collectedResults", results.size());

            // 완료 데이터 전송
            sendCompletionData(sessionId, resp);

        } catch (Exception e) {
            sendError(sessionId, "처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // 정렬 메서드 추가
    private List<LinkInfo> sortResultsByFixedOrder(List<LinkInfo> unsortedResults, List<CidEntry> cidList) {
        // CID를 키로 하는 맵 생성
        Map<Integer, LinkInfo> cidToResult = unsortedResults.stream()
            .collect(Collectors.toMap(LinkInfo::getCid, result -> result));

        // 고정 CID 리스트 순서대로 정렬된 결과 생성
        List<LinkInfo> sortedResults = cidList.stream()
            .map(cidEntry -> cidToResult.get(cidEntry.cid()))
            .filter(Objects::nonNull) // null 결과 제외
            .collect(Collectors.toList());

        System.out.printf("정렬 완료: %d개 결과를 고정 CID 순서로 정렬%n", sortedResults.size());

        return sortedResults;
    }

    private Map<String, String> collectSessionCookies(String url) throws Exception {
        Connection.Response response = Jsoup.connect(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("Cache-Control", "max-age=0")
            .header("sec-ch-ua", "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "document")
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-site", "none")
            .header("sec-fetch-user", "?1")
            .header("upgrade-insecure-requests", "1")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
            .timeout(5000)
            .ignoreHttpErrors(true)
            .followRedirects(true)
            .execute();

        if (response.statusCode() == 404 || response.url().toString().contains("pagenotfound")) {
            throw new Exception("URL이 유효하지 않거나 접근이 차단되었습니다.");
        }

        Map<String, String> sessionCookies = new HashMap<>(response.cookies());

        if (sessionCookies.isEmpty()) {
            throw new Exception("세션 쿠키를 받지 못했습니다.");
        }

        // 통화 관련 쿠키를 KRW로 강제 수정
        if (sessionCookies.containsKey("agoda.version.03")) {
            String versionCookie = sessionCookies.get("agoda.version.03");
            versionCookie = versionCookie.replaceAll("CurLabel=\\w+", "CurLabel=KRW");
            sessionCookies.put("agoda.version.03", versionCookie);
        }

        sessionCookies.put("agoda.price.01", "PriceView=2");

        return sessionCookies;
    }

    // 진행율 전송
    private void sendProgress(String sessionId, int current, int total) {
        SseEmitter emitter = sseEmitters.get(sessionId);
        if (emitter != null) {
            try {
                int percentage = (int) Math.round((double) current / total * 100);
                Map<String, Object> data = Map.of(
                    "type", "progress",
                    "percentage", percentage
                );
                emitter.send(SseEmitter.event().data(data));
            } catch (IOException e) {
                sseEmitters.remove(sessionId);
            }
        }
    }

    // 완료 데이터 전송
    private void sendCompletionData(String sessionId, Map<String, Object> result) {
        SseEmitter emitter = sseEmitters.get(sessionId);
        if (emitter != null) {
            try {
                Map<String, Object> data = Map.of(
                    "type", "complete",
                    "result", result
                );
                emitter.send(SseEmitter.event().data(data));
                emitter.complete();
                sseEmitters.remove(sessionId);
            } catch (IOException e) {
                sseEmitters.remove(sessionId);
            }
        }
    }

    // 오류 전송
    private void sendError(String sessionId, String message) {
        SseEmitter emitter = sseEmitters.get(sessionId);
        if (emitter != null) {
            try {
                Map<String, Object> data = Map.of(
                    "type", "error",
                    "message", message
                );
                emitter.send(SseEmitter.event().data(data));
                emitter.complete();
                sseEmitters.remove(sessionId);
            } catch (IOException e) {
                sseEmitters.remove(sessionId);
            }
        }
    }

    // 기존 메서드들 유지
    private LinkInfo fetchSequentiallyWithSession(String baseUrl, CidEntry entry, Map<String, String> sessionCookies) {
        String modUrl = baseUrl.replaceAll("cid=-?\\d+", "cid=" + entry.cid());
        String currency = extractCurrencyFromUrl(baseUrl);
        Map<String, String> updatedCookies = updateCookiesWithNewCid(sessionCookies, entry.cid());

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode root = fetchSecondaryDataJsonWithSession(modUrl, currency, entry.label(), updatedCookies);

                String hotel = root.path("hotelInfo").path("name").asText(null);
                double price = root.path("mosaicInitData").path("discount").path("cheapestPrice").asDouble(0);
                String apiCurrency = root.path("mosaicInitData").path("discount").path("currency").asText("UNKNOWN");

                boolean soldOut = price == 0;
                System.out.printf(
                    soldOut
                        ? "✗ %s (CID: %d) - 품절 (currency: %s)%n"
                        : "✓ %s (CID: %d) - 가격: %.2f (currency: %s)%n",
                    entry.label(), entry.cid(), price, apiCurrency
                );

                return new LinkInfo(entry.label(), entry.cid(), modUrl, price, soldOut, hotel);

            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    System.out.printf("✗ %s (CID: %d) - 실패: %s%n", entry.label(), entry.cid(), e.getMessage());
                    return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null);
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return new LinkInfo(entry.label(), entry.cid(), modUrl, 0, true, null);
    }

    private Map<String, String> updateCookiesWithNewCid(Map<String, String> originalCookies, int newCid) {
        Map<String, String> updatedCookies = new HashMap<>(originalCookies);
        String newCidStr = String.valueOf(newCid);

        String currentTime = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String currentTimeShort = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm"));

        if (updatedCookies.containsKey("agoda.firstclicks")) {
            String firstClicks = updatedCookies.get("agoda.firstclicks");
            firstClicks = firstClicks.replaceFirst("\\d+", newCidStr);
            firstClicks = firstClicks.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", currentTime);
            updatedCookies.put("agoda.firstclicks", firstClicks);
        }

        if (updatedCookies.containsKey("agoda.lastclicks")) {
            String lastClicks = updatedCookies.get("agoda.lastclicks");
            lastClicks = lastClicks.replaceFirst("\\d+", newCidStr);
            lastClicks = lastClicks.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", currentTime);
            updatedCookies.put("agoda.lastclicks", lastClicks);
        }

        if (updatedCookies.containsKey("agoda.landings")) {
            String landings = updatedCookies.get("agoda.landings");
            landings = landings.replaceFirst("\\d+", newCidStr);
            landings = landings.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", currentTime);
            updatedCookies.put("agoda.landings", landings);
        }

        if (updatedCookies.containsKey("agoda.attr.03")) {
            String attr03 = updatedCookies.get("agoda.attr.03");
            String newItem = String.format("%s$%s$%s",
                newCidStr,
                currentTimeShort,
                extractTagFromUrl(originalCookies.getOrDefault("agoda.firstclicks", "")));

            if (attr03.startsWith("ATItems=")) {
                attr03 = "ATItems=" + newItem + "|" + attr03.substring(8);
            } else {
                attr03 = "ATItems=" + newItem;
            }
            updatedCookies.put("agoda.attr.03", attr03);
        }

        if (updatedCookies.containsKey("agoda.attr.fe")) {
            String attrFe = updatedCookies.get("agoda.attr.fe");
            attrFe = attrFe.replaceFirst("\\d+", newCidStr);
            attrFe = attrFe.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}", currentTime);
            updatedCookies.put("agoda.attr.fe", attrFe);
        }

        return updatedCookies;
    }

    private String extractTagFromUrl(String cookieValue) {
        try {
            String[] parts = cookieValue.split("\\|\\|");
            if (parts.length > 1) {
                return parts[1];
            }
        } catch (Exception e) {
            // 추출 실패 시 기본값
        }
        return "eeeb2a37-a3e0-4932-8325-55d6a8ba95a4";
    }

    // 1.5초 대기 추가된 fetchSecondaryDataJsonWithSession 메서드
    private JsonNode fetchSecondaryDataJsonWithSession(String hotelPageUrl, String currency, String debugLabel, Map<String, String> sessionCookies) throws Exception {
        Document doc = Jsoup.connect(hotelPageUrl)
            .cookies(sessionCookies)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .timeout((int) Duration.ofSeconds(15).toMillis())
            .get();

        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        String content = script != null
            ? (script.data().isEmpty() ? script.text() : script.data())
            : "";
        String apiPath = content.split("apiUrl\\s*=\\s*\"")[1]
                                .split("\"")[0]
                                .replace("&amp;", "&");
        String apiUrl = "https://www.agoda.com" + apiPath;

        String cookieHeader = sessionCookies.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("; "));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Accept", "*/*")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .header("ag-language-locale", "ko-kr")
            .header("Cookie", cookieHeader)
            .header("cr-currency-code", "KRW")
            .header("cr-currency-id", "26")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", hotelPageUrl)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> apiResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // JSON이 완전히 로드되도록 1.5초 대기
        try {
            Thread.sleep(1500);
            System.out.printf("[%s] JSON 완전 로드를 위한 1.5초 대기 완료%n", debugLabel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("대기 중 인터럽트 발생: " + e.getMessage());
        }

        return mapper.readTree(apiResponse.body());
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

        public String getLabel() {
            return label;
        }
        public int getCid() {
            return cid;
        }
        public String getUrl() {
            return url;
        }
        public double getPrice() {
            return price;
        }
        public boolean isSoldOut() {
            return soldOut;
        }
        public String getHotel() {
            return hotel;
        }
    }
}
