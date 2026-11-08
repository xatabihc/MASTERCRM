package com.mastercrm.offline;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure document rendering: no Activity, content resolver, UI or CRM mutations.
 * The caller owns the output stream and decides where to save the document.
 */
public final class OrderPdfWriter {
    private OrderPdfWriter() {}

    public static void write(CrmStore.Job job, String date, OutputStream target)
            throws IOException {
        if(job==null||target==null)throw new IllegalArgumentException("Нет заказа или файла");
        PdfDocument pdf=new PdfDocument();
        try {
            PdfDocument.Page page=pdf.startPage(
                    new PdfDocument.PageInfo.Builder(595,842,1).create());
            android.graphics.Canvas canvas=page.getCanvas();
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(18,31,51));
            p.setTextSize(24);
            p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            canvas.drawText("MasterCRM — заказ-наряд",42,66,p);
            p.setTextSize(15);
            p.setTypeface(Typeface.DEFAULT);
            int y=105;
            String[] lines={"Номер: "+job.number,
                    "Дата: "+date,
                    "Клиент: "+job.clientName,
                    "Телефон: "+job.clientPhone,
                    "Работа: "+job.title,
                    "Статус: "+job.status,
                    "Срок: "+job.dueDate,
                    "Стоимость: "+Money.format(job.price),
                    "Оплачено: "+Money.format(job.paid),
                    "Долг: "+Money.format(Money.outstanding(job.price,job.paid))};
            for(String line:lines) {
                canvas.drawText(shorten(line,68),42,y,p);
                y+=31;
            }
            y+=18;
            p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            canvas.drawText("Описание работ:",42,y,p);
            y+=28;
            p.setTypeface(Typeface.DEFAULT);
            for(String line:wrapText(job.description,60)) {
                if(y>740)break;
                canvas.drawText(line,42,y,p);
                y+=23;
            }
            p.setTextSize(11);
            p.setColor(Color.GRAY);
            canvas.drawText("Информационный документ, не кассовый чек.",42,798,p);
            pdf.finishPage(page);
            pdf.writeTo(target);
        } finally {
            pdf.close();
        }
    }

    private static String shorten(String value,int max) {
        return value.length()<=max?value:value.substring(0,max-1)+"…";
    }

    private static List<String> wrapText(String value,int width) {
        List<String> result=new ArrayList<>();
        if(value==null||value.trim().isEmpty())return result;
        for(String paragraph:value.split("\\n",-1)) {
            StringBuilder line=new StringBuilder();
            for(String word:paragraph.trim().split("\\s+")) {
                if(line.length()+word.length()+1>width&&line.length()>0) {
                    result.add(line.toString());
                    line.setLength(0);
                }
                if(word.length()>width)word=shorten(word,width);
                if(line.length()>0)line.append(' ');
                line.append(word);
            }
            result.add(line.toString());
        }
        return result;
    }
}
