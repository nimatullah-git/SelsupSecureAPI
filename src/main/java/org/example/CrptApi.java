package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author nimatullah
 */
public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final AtomicInteger requestCount;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Lock lock;
    private final Condition limitNotReached;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.requestCount = new AtomicInteger(0);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.lock = new ReentrantLock();
        this.limitNotReached = lock.newCondition();

        // Планируем задачу для сброса счётчика запросов
        scheduler.scheduleAtFixedRate(() -> {
            lock.lock();
            try {
                requestCount.set(0);
                limitNotReached.signalAll();
            } finally {
                lock.unlock();
            }
        }, 0, getResetInterval(), timeUnit);
    }

    public void createDocument(Document document, String signature) throws IOException, InterruptedException {
        lock.lock();
        try {
            // Блокируем, если лимит запросов достигнут
            while (requestCount.get() >= requestLimit) {
                limitNotReached.await();
            }
            // Увеличиваем счётчик запросов
            requestCount.incrementAndGet();
        } finally {
            lock.unlock();
        }

        // Создаём тело запроса
        String jsonBody = objectMapper.writeValueAsString(document);

        // Создаём HTTP запрос
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        // Отправляем запрос и получаем ответ
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Обрабатываем ответ по необходимости (например, логируем его, проверяем статус-код и т.д.)
        if (response.statusCode() != 200) {
            System.err.println("Ошибка: " + response.statusCode() + " - " + response.body());
        } else {
            System.out.println("Ответ: " + response.body());
        }
    }

    private long getResetInterval() {
        return switch (timeUnit) {
            case SECONDS -> 1;
            case MINUTES -> 60;
            case HOURS -> 3600;
            default -> throw new IllegalArgumentException("Неподдерживаемая единица времени: " + timeUnit);
        };
    }

    // Внутренний класс для Document
    public static class Document {
        public Description description;
        public String doc_id;
        public String doc_status;
        public String doc_type;
        public boolean importRequest;
        public String owner_inn;
        public String participant_inn;
        public String producer_inn;
        public String production_date;
        public String production_type;
        public Product[] products;
        public String reg_date;
        public String reg_number;

        public static class Description {
            public String participantInn;
        }

        public static class Product {
            public String certificate_document;
            public String certificate_document_date;
            public String certificate_document_number;
            public String owner_inn;
            public String producer_inn;
            public String production_date;
            public String tnved_code;
            public String uit_code;
            public String uitu_code;
        }
    }

    public static void main(String[] args) {
        try {
            CrptApi api = new CrptApi(TimeUnit.SECONDS, 5);
            Document doc = new Document();
            // Инициализируйте объект doc по необходимости
            api.createDocument(doc, "signature");
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
