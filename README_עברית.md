# WhatsApp השתקה מלאה לקבוצות — מודול LSPosed

## מה המודול עושה

מוסיף אפשרות "השתק לגמרי" לתפריט שלוש הנקודות (⋮) שמופיע ברשימת הצ'אטים
לאחר לחיצה ארוכה על קבוצה. הפריט מופיע **רק עבור קבוצות**, לא עבור צ'אטים פרטיים.

### תהליך השימוש
1. לחץ לחיצה ארוכה על קבוצה ברשימת הצ'אטים
2. לחץ על שלוש הנקודות (⋮) בתפריט שנפתח
3. בחר **"השתק לגמרי"**
4. לביטול: חזור על השלבים ובחר **"בטל השתקה לגמרי"**

### מה נחסם
- צלילי התראה
- רטט
- חלוניות קופצות (heads-up)
- אייקון בשורת המצב
- הודעות **לא נמחקות** — ניתן לקרוא אותן בכל עת

---

## מבנה קבצי המקור

```
WhatsAppCompleteMute/
├── app/src/main/
│   ├── AndroidManifest.xml          — Manifest עם permission לIPC
│   ├── assets/xposed_init           — נקודת כניסה ל-LSPosed
│   ├── kotlin/com/eliav/wacompletemute/
│   │   ├── MuteHook.kt              — Hook ראשי (רץ בתהליך WhatsApp)
│   │   ├── MuteNotificationService.kt — שכבת גיבוי (NotificationListenerService)
│   │   ├── MuteStateReceiver.kt     — IPC receiver (כותב SharedPreferences)
│   │   ├── MuteStore.kt             — מאגר הגדרות
│   │   └── MainActivity.kt          — מסך סטטוס
│   └── res/
│       ├── layout/activity_main.xml
│       └── values/strings.xml
├── app/build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## מנגנון הפעולה

### שכבה 1 — Hook בתוך WhatsApp (חסימת צליל לפני ה-OS)

**מה מוחלף:** `Activity.startActionMode()` על `com.whatsapp.HomeActivity`

כאשר המשתמש לוחץ לחיצה ארוכה, WhatsApp מפעיל Contextual Action Bar (CAB).
ה-Hook עוטף את ה-`ActionMode.Callback` המקורי ומוסיף פריט תפריט.
לחיצה על "השתק לגמרי" מוסיפה את ה-JID ל-`mutedGroupsInProcess`.

בנוסף, ה-Hook עוכב על `NotificationManager.notify()` **בתוך תהליך WhatsApp**,
ומסיר `sound` ו-`vibrate` מה-Notification לפני שנשלח ל-OS. זה קורה לפני
שהצליל מופעל בפועל.

### שכבה 2 — NotificationListenerService (גיבוי)

`MuteNotificationService` מקבל callback לאחר שהOS קיבל את ההתראה ומבטל אותה.
זה מבטיח חסימה של badge ולוח ההתראות, גם אם שכבה 1 לא הצליחה לחסום הצליל.

### IPC (תקשורת בין תהליכים)

```
[תהליך WhatsApp]          [תהליך המודול]
MuteHook.kt
  │
  ├─ sendBroadcast()  ──► MuteStateReceiver.kt
  │                             │
  │                       MuteStore.setMuted()
  │                             │
  │                       MuteNotificationService.reloadMutedGroups()
  │
  └─ (in-memory set)      ← לשכבה 1 בלבד
```

השימוש ב-`BroadcastReceiver` עם `android:permission="signature"` מבטיח:
- רק המודול שלנו יכול לעדכן את הרשימה
- אין גישה חוצת-תהליכים לקבצים
- הגדרות נשמרות לאחר הפעלה מחדש

---

## דרישות התקנה

### דרישות חובה
- מכשיר Android 8.0 (API 26) ומעלה
- Magisk (rooted)
- LSPosed (JingMatrix fork) מותקן
- WhatsApp הרגיל (`com.whatsapp`) — לא Business

### שלבי ההתקנה

#### שלב 1 — בנייה והתקנה
1. פתח פרויקט ב-Android Studio
2. המתן ל-Gradle sync
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. התקן את ה-APK על המכשיר

#### שלב 2 — הפעלה ב-LSPosed
1. פתח LSPosed Manager
2. עבור ל-**Modules**
3. הפעל **WhatsApp השתקה מלאה**
4. לחץ על המודול ← **Scope** ← הפעל על **com.whatsapp**
5. הפעל מחדש את WhatsApp (force stop + open)

#### שלב 3 — גישה להתראות (חובה לשכבה 2)
1. פתח את אפליקציית המודול
2. לחץ על **"נדרשת גישה להתראות"**
3. הפעל את המודול ברשימה
4. חזור לאפליקציה ובדוק שמופיע ✓

---

## מגבלות ידועות (לפני בדיקה על מכשיר)

| מגבלה | הסיבה | סטטוס |
|-------|-------|-------|
| הצליל עשוי להישמע שבריר שנייה לפני החסימה | ה-OS מפעיל צליל לפני NotificationListenerService | מצומצם ע"י שכבה 1 (Hook בתהליך WA) |
| זיהוי JID מה-ActionMode לא מאומת | אין גישה ל-decompile של גרסה עכשווית | **נדרש לוג בזמן ריצה** |
| `com.whatsapp.HomeActivity` עשוי לשנות שם בעדכון | WhatsApp משנה שמות בין גרסאות | `findClassIfExists` מדלג בחינניות |
| AppCompat startSupportActionMode עשוי לא לעבור Activity.startActionMode | תלוי בגרסת AppCompat של WhatsApp | שני variant-ים מ-hooked |
| NotificationChannel ב-IMPORTANCE_HIGH יתעלם מהסרת sound | Android 8+ — channel settings גוברות על notification settings | ⚠️ מגבלה אמיתית, ניתן לפתור ע"י hook על NotificationChannel |

---

## פתרון בעיות

### הפריט לא מופיע בתפריט
1. בדוק ב-LSPosed LogCat: `filter: WaCompleteMute`
2. חפש `loaded` — אם לא מופיע, המודול לא נטען
3. אם מופיע `HomeActivity not found` — גרסת WhatsApp שינתה שם
4. ודא שה-Scope כולל `com.whatsapp`

### הצליל עדיין מופעל
1. בדוק שגישת ההתראות מאושרת
2. בדוק ב-LogCat: `stripped sound/vibrate for` — אם מופיע, שכבה 1 פעלה
3. אם לא מופיע: WhatsApp משתמש ב-NotificationChannel ישיר — ראה מגבלות

### הגדרות לא נשמרות לאחר הפעלה מחדש
1. בדוק שה-`MuteStateReceiver` רשום ב-LSPosed
2. בדוק ב-LogCat: `IPC received`

---

## רישיון
MIT — לשימוש חינמי לכל מטרה.
