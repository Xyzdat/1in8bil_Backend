package com.life.life_backend.engine.adult;

import java.util.*;
import static com.life.life_backend.engine.adult.AdultState.*;
import static com.life.life_backend.engine.adult.StoryProgress.Result.*;

/** Gameplay labour model, not a claim about national wages or employment law. */
final class CareerSystem {
    static final Set<String> ACTIONS=Set.of("study","scholarship","study_review","graduate","apply_job","job_referral",
            "probation","promotion","promotion_review","work_talk","work_review","quit","layoff","retrain","qualify",
            "business","business_review","business_growth","restructure","bankrupt","business_recovery","rest","burnout_review",
            "retire","retirement_review","work_harder","apply_part_time");
    StoryProgress.Result act(String action, AdultWorld w, StoryProgress p) {
        var c=w.career;
        return switch(action) {
            case "study", "scholarship" -> {
                if (action.equals("scholarship") && !w.chance(.25+w.state.stats().intelligence()/200d))
                    yield end("Конкурс на стипендию оказался выше твоего результата. Можно попробовать другой путь и вернуться к учёбе позже.");
                w.education=new Education("HIGHER","Прикладные науки",w.month(),w.month()+48,"IN_PROGRESS");
                yield next("Ты поступаешь на программу. Учёбу предстоит совмещать с остальной жизнью в течение четырёх лет.");
            }
            case "study_review" -> {
                if (w.state.stats().health()<20 || !w.chance(.55+w.traits.diligence()/250d)) {
                    w.education=new Education("SECONDARY",w.education.field(),w.education.startedMonth(),null,"INTERRUPTED");
                    yield end("Нагрузка оказалась слишком большой. Учёба прервана; полученный опыт остаётся, диплом пока не получен.");
                }
                w.stats(0,2,4,0); yield next("Промежуточные экзамены сданы. Ты выбираешь тему практической работы.");
            }
            case "graduate" -> {
                w.education=new Education("HIGHER",w.education.field(),w.education.startedMonth(),w.month(),"COMPLETED");
                qualify(w,2); w.stats(0,5,6,0); yield end("Ты завершаешь программу и получаешь квалификацию. Теперь доступны более сложные вакансии, но трудоустройство ещё предстоит.");
            }
            case "apply_job", "job_referral", "apply_part_time" -> {
                if (!RetirementPolicy.canWork(w)) yield end("Рабочий этап завершён. На пенсии доступны другие занятия и время с близкими.");
                if(w.employed()) yield end("Ты уже работаешь. Это обращение к работодателю больше не актуально.");
                double probability=.25+w.traits.diligence()/400d+w.state.stats().health()/500d+c.qualification()*.06;
                if(action.equals("job_referral")) probability+=.18;
                if(!w.chance(probability)) { w.stats(0,-2,0,0); yield end("После собеседования работодатель выбрал другого кандидата. Поиск остаётся открытым."); }
                String[] jobs=c.qualification()>=2||"HIGHER".equals(w.education.level())
                        ? new String[]{"Инженер","Аналитик","Координатор проектов"}:new String[]{"Мастер ремонта","Оператор склада","Помощник повара","Специалист поддержки"};
                String job=jobs[w.random.nextInt(jobs.length)];
                String employer="Компания «"+List.of("Мастерская","Горизонт","Практика","Соседи","Линия").get(w.random.nextInt(5))+"»";
                long pay=w.salary(w.cost(14+4*c.qualification()+w.random.nextInt(9)),job);
                if(w.sim.calibration()!=null) {
                    p.put("wageModel",w.sim.calibration().modelVersion()); p.put("wageCountry",w.location.countryCode());
                    p.put("wageFactor",String.valueOf(CalibratedWages.factor(w.sim.calibration(),w.location.countryCode(),job)));
                }
                boolean partTime=action.equals("apply_part_time");
                if(partTime) pay=pay*60/100;
                var history=new ArrayList<>(c.history()); String id=w.id("employment");
                history.add(new Employment(id,job,employer,w.month(),null,null)); p.put("employment",id);
                w.career=new Career("PROBATION",job,"Услуги и производство",employer,partTime?"Неполный день":"Начальная позиция",pay,w.month(),c.experienceMonths(),c.qualification(),partTime?70:60,45,partTime?10:20,c.monthlyPension(),history);
                yield next("Тебя принимают на испытательный срок: "+job+(partTime?" (неполный день, 60% оплаты полной ставки)":"")+". Зарплата начнёт поступать за следующие прожитые месяцы.");
            }
            case "probation" -> {
                if(!sameJob(w,p)) yield end("К этому моменту эта работа уже закончилась; испытательный срок закрыт.");
                if(!w.chance(.5+w.traits.diligence()/250d+w.state.stats().health()/600d)) { leave(w,"Испытание не пройдено"); yield end("После испытательного срока договор не продлили. Приобретённый опыт поможет в новом поиске."); }
                update(w,"EMPLOYED",c.monthlyPay(),70,75,c.qualification());
                yield next("Испытательный срок пройден. Ты остаёшься в команде.");
            }
            case "promotion" -> { p.put("employment",currentJob(c)); yield next("Ты обсуждаешь результаты и дополнительные обязанности с руководителем."); }
            case "promotion_review" -> {
                if(!sameJob(w,p)) yield end("Ты уже покинул эту работу. Пересмотр должности отменён.");
                if(!w.chance(.20+c.satisfaction()/250d+w.traits.diligence()/400d)) yield end("Повышение пока не согласовали. Текущая должность и зарплата сохраняются.");
                update(w,c.status(),c.monthlyPay()*115/100,Math.min(95,c.satisfaction()+8),c.stability(),c.qualification()+1);
                yield end("Руководитель согласовал повышение и увеличение зарплаты на 15%. Вместе с оплатой выросла ответственность.");
            }
            case "work_talk" -> { p.put("employment",currentJob(c)); w.stats(0,1,0,1); yield next("Ты обсуждаешь спорные обязанности и предлагаешь конкретное разделение работы."); }
            case "work_review" -> {
                if(!sameJob(w,p)) yield end("Прежний рабочий конфликт больше не относится к текущему месту работы.");
                boolean good=w.chance(.45+w.traits.sociability()/300d);
                update(w,c.status(),c.monthlyPay(),AdultWorld.clamp(c.satisfaction()+(good?12:-12)),AdultWorld.clamp(c.stability()+(good?5:-8)),c.qualification());
                yield end(good?"Команда договорилась о границах ответственности. Работать стало спокойнее.":"Договориться полностью не удалось. Напряжение в команде сохраняется.");
            }
            case "quit" -> { leave(w,"Увольнение по собственному решению"); yield end("Ты уходишь с работы. Зарплата больше не начисляется, поиск нового места может начаться позже."); }
            case "layoff" -> { leave(w,"Сокращение"); w.stats(0,-7,0,0); yield next("Работодатель закрывает твою должность. Накопления сохраняются, но зарплата прекращается."); }
            case "retrain" -> { p.put("education","training"); yield next("Ты начинаешь практическое обучение новой специальности. Результат потребует нескольких месяцев занятий."); }
            case "qualify" -> { qualify(w,1); w.stats(0,3,3,0); yield end("Практический курс завершён. Квалификация повышена; новые навыки учитываются при найме и продвижении."); }
            case "business" -> {
                if (!RetirementPolicy.canWork(w)) yield end("Рабочий этап завершён. Новое дело на пенсии не открывается.");
                if(w.employed()) leave(w,"Открытие своего дела");
                c=w.career; var history=new ArrayList<>(c.history()); String id=w.id("employment"); p.put("employment",id);
                history.add(new Employment(id,"Предприниматель","Своя мастерская",w.month(),null,null));
                w.career=new Career("BUSINESS","Предприниматель","Малый бизнес","Своя мастерская","Владелец",w.cost(12),w.month(),c.experienceMonths(),c.qualification(),65,40,50,c.monthlyPension(),history);
                yield next("Ты открываешь небольшое дело. Первые месяцы покажут, удастся ли найти постоянных клиентов.");
            }
            case "business_review", "business_growth" -> {
                if(!sameJob(w,p)||!c.status().equals("BUSINESS")) yield end("Это дело уже закрыто. Проверка результатов завершена.");
                boolean good=w.chance(.35+w.traits.diligence()/300d+c.qualification()*.03);
                update(w,"BUSINESS",Math.max(w.cost(3),c.monthlyPay()*(good?125:65)/100),good?75:40,good?65:25,c.qualification());
                yield action.equals("business_review")?next(good?"Появились постоянные клиенты. Доход бизнеса вырос.":"Клиентов меньше ожидаемого. Доход сократился; придётся пересмотреть расходы.")
                        :end(good?"Дело удержалось на рынке и приносит больший доход.":"Прибыль снизилась. История бизнеса продолжается с более скромными результатами.");
            }
            case "restructure" -> { p.put("employment",currentJob(c)); update(w,c.status(),c.monthlyPay()*80/100,45,40,c.qualification()); yield next("Ты сокращаешь объём дела, чтобы уменьшить риск. Доход временно снижается."); }
            case "bankrupt" -> { leave(w,"Закрытие убыточного дела"); w.flags.add("business-closed"); yield end("Дело закрыто. Имеющиеся личные долги сохраняются: списание долгов не моделируется автоматически."); }
            case "business_recovery" -> {
                if(!sameJob(w,p)) yield end("Дело уже закрыто. Антикризисный план прекращён.");
                if(w.chance(.5)) { update(w,c.status(),c.monthlyPay()*120/100,60,60,c.qualification()); yield end("Сокращение расходов помогло стабилизировать дело."); }
                leave(w,"Не удалось восстановить бизнес"); yield end("План не окупился, и ты закрываешь дело. Непогашенные обязательства остаются в бюджете.");
            }
            case "rest" -> { w.stats(4,5,0,0); if(w.employed()) update(w,c.status(),c.monthlyPay()*90/100,65,c.stability(),c.qualification()); yield next("Ты уменьшаешь нагрузку. Доход снижается, зато появляется время восстановиться."); }
            case "work_harder" -> { w.stats(-5,-3,1,0); if(w.employed()) update(w,c.status(),c.monthlyPay()*105/100,c.satisfaction()-5,c.stability(),c.qualification()); yield next("Ты берёшь больше задач. Доход немного вырос, но усталость уже сказывается на здоровье."); }
            case "burnout_review" -> { w.stats(3,3,0,0); yield end("Ты пересматриваешь привычный ритм. Этот период оставил опыт того, сколько нагрузки удаётся выдерживать."); }
            case "retire" -> {
                RetirementPolicy.retire(w);
                yield next("Ты прекращаешь текущую работу и переходишь на игровую пенсионную выплату. Появляется время для семьи, отдыха и новых занятий.");
            }
            case "retirement_review" -> { w.stats(2,5,1,1); yield end("Появилось время на занятия, которые раньше откладывались. Ты находишь новый ритм вне работы."); }
            default -> throw new IllegalArgumentException(action);
        };
    }
    static void qualify(AdultWorld w,int points) { var c=w.career; update(w,c.status(),c.monthlyPay(),c.satisfaction(),c.stability(),Math.min(10,c.qualification()+points)); }
    static void update(AdultWorld w,String status,long pay,int satisfaction,int stability,int qualification) {
        var c=w.career; w.career=new Career(status,c.occupation(),c.industry(),c.employer(),qualification>c.qualification()?"Повышенная квалификация":c.position(),
                pay,c.beganMonth(),c.experienceMonths(),qualification,AdultWorld.clamp(satisfaction),AdultWorld.clamp(stability),c.risk(),c.monthlyPension(),c.history());
    }
    static void leave(AdultWorld w,String reason) {
        var c=w.career; var history=c.history().stream().map(e -> e.endedMonth()==null?new Employment(e.id(),e.occupation(),e.employer(),e.beganMonth(),w.month(),reason):e).toList();
        w.career=new Career("UNEMPLOYED",null,null,null,null,0,w.month(),c.experienceMonths(),c.qualification(),45,0,0,c.monthlyPension(),history);
    }
    static String currentJob(Career c) { return c.history().isEmpty()?"none":c.history().getLast().id(); }
    static boolean sameJob(AdultWorld w,StoryProgress p) { return w.employed() && Objects.equals(p.get("employment"),currentJob(w.career)); }
}
