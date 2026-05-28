package com.example.s_master;

class Message {
    static final int TYPE_AGENT = 0;
    static final int TYPE_USER = 1;
    static final int TYPE_SYSTEM = 2;

    int type;
    String text;
    long timestamp;

    Message(int type, String text) {
        this.type = type;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }
}
