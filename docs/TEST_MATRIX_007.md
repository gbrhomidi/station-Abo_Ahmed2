# مصفوفة الاختبارات الوظيفية والمرئية الشاملة (TEST MATRIX 007)

تغطي هذه المصفوفة المتطلبات المحددة في بروتوكول `GLOBAL-SMS-UI-RESEARCH-AND-ADAPTATION-007`.

## 1. الاختبارات المرئية (UI / Visual Tests)
| المعرّف | الهدف | الوصف | النتيجة المتوقعة |
|---|---|---|---|
| `UI-001` | RTL & Arabic Support | التحقق من محاذاة العناصر من اليمين لليسار في جميع الشاشات. | لا تداخل في النصوص، الأيقونات في مكانها الصحيح. |
| `UI-002` | Dark/Light Mode | التبديل بين الوضع المظلم والفاتح. | تباين الألوان صحيح (Contrast Ratio > 4.5)، لا نصوص مخفية. |
| `UI-003` | Responsive & Touch | اختبار الواجهة على شاشات صغيرة (Mobile) وكبيرة (Tablet). | الجداول قابلة للتمرير أفقيًا (Scrollable)، الأزرار سهلة اللمس (Touch Target > 44px). |
| `UI-004` | Empty & Error States | عرض الشاشة بدون بيانات (Empty) أو عند فشل الاتصال (Error). | ظهور رسائل واضحة وأيقونات دلالية بدلًا من شاشة بيضاء. |

## 2. اختبارات WebView و Android Bridge
| المعرّف | الهدف | الوصف | النتيجة المتوقعة |
|---|---|---|---|
| `WEB-001` | Bridge Availability | فتح الشاشة خارج التطبيق (Browser). | ظهور `Offline Banner` يوضح غياب الجسر. |
| `WEB-002` | Database Injection | تمرير JSON يحتوي على أكواد ضارة (SQL Injection/XSS) عبر Bridge. | يتم تنظيف المدخلات (Sanitization) أو رفضها بواسطة `SecurityValidator`. |

## 3. اختبارات مسار SMS (SMS Operations)
| المعرّف | الهدف | الوصف | النتيجة المتوقعة |
|---|---|---|---|
| `SMS-001` | Conversation State | التحقق من عرض `cognitive_state` الصحيح في واجهة `messages.html`. | تظهر شارة (Badge) مثل `CONFIRMATION_REQUIRED` أو `EXECUTING`. |
| `SMS-002` | Duplicate SMS | استقبال رسالتين متطابقتين في نفس الثانية. | يتم تطبيق Idempotency ولا تُحفظ إلا رسالة واحدة. |

## 4. اختبارات الذكاء الاصطناعي (AI & Monitoring)
| المعرّف | الهدف | الوصف | النتيجة المتوقعة |
|---|---|---|---|
| `AI-001` | Circuit Breaker | فشل مزود الذكاء الاصطناعي 4 مرات متتالية. | يتغير `is_cooldown` إلى `true`، ويتحول لونه إلى الأحمر في `ai-assistant.html`. |
| `AI-002` | Real SQLite Health | استرجاع بيانات صحة AI من `DatabaseHelper` وليس من الذاكرة العشوائية. | بيانات النجاح والفشل تتطابق مع جدول `ai_providers_health` في SQLite. |
| `AI-003` | No Secrets Exposed | فحص JSON المُرجع من `getAiHealthStatus`. | لا يحتوي على `api_key` أو `secret_token`. |
