package com.leafscan;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lê o labels.txt (nomes do PlantVillage, na ordem das saídas) e traduz para português. */
public final class Labels {
    private static final Map<String, String> PT = new HashMap<>();
    static {
        PT.put("Apple___Apple_scab", "Maçã — sarna");
        PT.put("Apple___Black_rot", "Maçã — podridão-negra");
        PT.put("Apple___Cedar_apple_rust", "Maçã — ferrugem");
        PT.put("Apple___healthy", "Maçã — saudável");
        PT.put("Blueberry___healthy", "Mirtilo — saudável");
        PT.put("Cherry_(including_sour)___Powdery_mildew", "Cereja — oídio");
        PT.put("Cherry_(including_sour)___healthy", "Cereja — saudável");
        PT.put("Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot", "Milho — cercosporiose (mancha-cinzenta)");
        PT.put("Corn_(maize)___Common_rust_", "Milho — ferrugem-comum");
        PT.put("Corn_(maize)___Northern_Leaf_Blight", "Milho — helmintosporiose");
        PT.put("Corn_(maize)___healthy", "Milho — saudável");
        PT.put("Grape___Black_rot", "Uva — podridão-negra");
        PT.put("Grape___Esca_(Black_Measles)", "Uva — esca");
        PT.put("Grape___Leaf_blight_(Isariopsis_Leaf_Spot)", "Uva — mancha-das-folhas (isariopsis)");
        PT.put("Grape___healthy", "Uva — saudável");
        PT.put("Orange___Haunglongbing_(Citrus_greening)", "Laranja — greening (HLB)");
        PT.put("Peach___Bacterial_spot", "Pêssego — mancha-bacteriana");
        PT.put("Peach___healthy", "Pêssego — saudável");
        PT.put("Pepper,_bell___Bacterial_spot", "Pimentão — mancha-bacteriana");
        PT.put("Pepper,_bell___healthy", "Pimentão — saudável");
        PT.put("Potato___Early_blight", "Batata — pinta-preta");
        PT.put("Potato___Late_blight", "Batata — requeima");
        PT.put("Potato___healthy", "Batata — saudável");
        PT.put("Raspberry___healthy", "Framboesa — saudável");
        PT.put("Soybean___healthy", "Soja — saudável");
        PT.put("Squash___Powdery_mildew", "Abóbora — oídio");
        PT.put("Strawberry___Leaf_scorch", "Morango — queima-das-folhas");
        PT.put("Strawberry___healthy", "Morango — saudável");
        PT.put("Tomato___Bacterial_spot", "Tomate — mancha-bacteriana");
        PT.put("Tomato___Early_blight", "Tomate — pinta-preta");
        PT.put("Tomato___Late_blight", "Tomate — requeima");
        PT.put("Tomato___Leaf_Mold", "Tomate — mofo-das-folhas");
        PT.put("Tomato___Septoria_leaf_spot", "Tomate — septoriose");
        PT.put("Tomato___Spider_mites Two-spotted_spider_mite", "Tomate — ácaro-rajado");
        PT.put("Tomato___Target_Spot", "Tomate — mancha-alvo");
        PT.put("Tomato___Tomato_Yellow_Leaf_Curl_Virus", "Tomate — vírus do enrolamento amarelo");
        PT.put("Tomato___Tomato_mosaic_virus", "Tomate — mosaico");
        PT.put("Tomato___healthy", "Tomate — saudável");
    }

    public final List<String> raw = new ArrayList<>();

    public Labels(Context ctx) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("labels.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) raw.add(line);
            }
        }
    }

    public int size() {
        return raw.size();
    }

    public String display(int i) {
        String name = raw.get(i);
        String pt = PT.get(name);
        return pt != null ? pt : name.replace("___", " — ").replace('_', ' ');
    }
}
