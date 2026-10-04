package com.tripinvoice.itinerary.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.GenericGenerator;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import javax.persistence.Table;

@Entity
@Table(name = "itinerary")
public class Itinerary {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    private UUID id;

    @Column(name = "booking_reference", nullable = false, updatable = false, length = 20)
    private String bookingReference;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "traveler_name", nullable = false, length = 120)
    private String travelerName;

    @Column(name = "traveler_email", nullable = false, length = 254)
    private String travelerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItineraryStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TravelSegment> segments = new ArrayList<>();

    protected Itinerary() {
        // for JPA
    }

    public static Itinerary confirmed(String bookingReference, String customerId, String travelerName,
                                      String travelerEmail, String currency, Instant createdAt) {
        Itinerary itinerary = new Itinerary();
        itinerary.bookingReference = bookingReference;
        itinerary.customerId = customerId;
        itinerary.travelerName = travelerName;
        itinerary.travelerEmail = travelerEmail;
        itinerary.currency = currency;
        itinerary.status = ItineraryStatus.CONFIRMED;
        itinerary.createdAt = createdAt;
        return itinerary;
    }

    /** Adds a segment and keeps the total in sync. The total is always computed server-side. */
    public void addSegment(TravelSegment segment) {
        segment.attachTo(this);
        segments.add(segment);
        totalAmount = totalAmount.add(segment.getAmount());
    }

    public UUID getId() { return id; }
    public String getBookingReference() { return bookingReference; }
    public String getCustomerId() { return customerId; }
    public String getTravelerName() { return travelerName; }
    public String getTravelerEmail() { return travelerEmail; }
    public ItineraryStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }

    /** Segments in a deterministic order: by start date, then description. */
    public List<TravelSegment> getSegments() {
        return segments.stream()
                .sorted(Comparator.comparing(TravelSegment::getStartDate)
                        .thenComparing(TravelSegment::getDescription))
                .toList();
    }
}
