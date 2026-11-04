package com.mygateapp.deliveries.enums;

public enum DeliveryCategory {
    FOOD("Food"),
    GROCERY("Grocery"),
    COURIER("Courier"),
    ECOMMERCE("E-commerce"),
    OTHER("Other");

    private final String label;

    DeliveryCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
