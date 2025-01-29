# Reminder bot

## О проекте

Reminder bot - телеграм бот который сохраняет события и уведомляет пользователя
в назначенное время.
События можно задавать в произвольном формате, например переслать из другого чата,
главное чтобы в них было указано время.  
Сообщения обрабатываются с помощью GPT после чего в случае успеха создаётся событие
о котором бот в назначенное время отправит пользователю уведомление.  
Для доступа к GPT используется *[gpt4free](https://github.com/xtekky/gpt4free)*
который запускается в docker-контейнере, после чего бот общается с ним по http.

## Использование

Развернуть бота можно используя docker compose, для этого нужно
указать api-ключ телеграм бота (token) в src/main/resources/bot.conf. Зарегистрировать api ключ
можно *[здесь](https://telegram.me/BotFather)*. Важно использовать docker compose,
а не docker-compose так как контейнерам нужна локальная сеть создаваемая docker-ом  
Бота можно *[потестить](https://telegram.me/reminder_1337_bot)* (скорее всего)  
## Функции
Перед использованием боту можно скинуть локацию из вашей тайм-зоны
, иначе по умолчанию он будет использовать UTC  
Пример событий с которым у бота есть шанс справиться:   
<i>"Прогулка с собакой через 10 минут"

"Привет! Экзамен пройдет 18 декабря в 18:00 (время лекции) в формате теста
с открытыми вопросами и задачами онлайн. Мы поделимся подробностями чуть позже,
а пока проверьте, что у вас есть аккаунт на нашем GitLab, он вам понадобится."  
</i>

Ещё лучше:  
<i>
"Codeforces Global Round 28 starts on the 19th of December at 14:35 UTC.
It is the fourth round of Codeforces Global Rounds 2024 supported by XTX Markets.
The round is open and rated for everybody!
The prizes for this round:
👕30 best participants get a t-shirt.
👕20 t-shirts are randomly distributed among those with ranks between 31 and 500, inclusive."
</i>

**/pop** - удалить последнее добавленное событие  
**/remove** <_название_> - удалить событие по имени

## Конфигурация  
Получив сообщение бот попытается обработать его одной из доступных
языковых моделей чтобы получить время и название события.
Зачастую, ему это не удасться, так как модель может быть недоступна
или вернёт некорректные данные, поэтому чтобы обработать сообщение
быстрее можно отправить запрос одновременно нескольким моделям и использовать
первый корректный ответ, за это отвечает переменная _gpt-count_ в bot.conf.
  
Пользователь может отправить сообщение в котором нет события
или ему просто не повезло и ни одна модель не справилась.
Тогда стоит прекратить попытки обработать сообщения и сказать 
пользователю что его событие не сохранилось, за это отвечает
_gpt-retries_  

Так же, количество запросов к GPT которые мы можем совершать ограничено.
Мы не хотим чтобы один пользователь использовал все наши ресурсы,
поэтому стоит ограничить частоту добавления событий одним пользователем.
За это отвечает _user-timeout_ (целое число секунд)

gpt-request-timeout - Время, которое бот будет
ожидать ответа одной модели (целое число секунд)

bdc-key - API ключ *[сервиса](https://www.bigdatacloud.com/account)* с помощью которого бот получает
информацию о тайм-зоне по локации, можно использовать мой.
