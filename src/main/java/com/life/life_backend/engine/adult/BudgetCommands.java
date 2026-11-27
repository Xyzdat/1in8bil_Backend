package com.life.life_backend.engine.adult;

import com.life.life_backend.web.ApiFailure;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.life.life_backend.economy.LocalBudget.money;
import static com.life.life_backend.engine.adult.AdultState.*;

final class BudgetCommands {
    static String blocked(AdultWorld w){
        if(w.state.status()==com.life.life_backend.engine.LifeState.Status.COMPLETE||w.state.stats().health()<=0)return "Жизнь завершена";
        if(w.state.pendingDecision()!=null)return "Сначала ответьте на ожидающее решение";
        if(w.age()<18)return "Управление бюджетом доступно с 18 лет";
        return null;
    }
    static String jobReason(AdultWorld w,BudgetCatalog.Job job){
        String base=blocked(w);if(base!=null)return base;
        if(w.age()<18||!RetirementPolicy.canWork(w))return "Работа доступна с 18 лет до пенсии";
        if(w.state.stats().health()<30)return "Недостаточно здоровья для этой нагрузки";
        if(w.education.status().equals("IN_PROGRESS")&&job.hours()>24)return "Очная учёба совместима только с нагрузкой до 24 часов";
        if(job.education().equals("HIGHER")&&!(w.education.level().equals("HIGHER")&&w.education.status().equals("COMPLETED")))return "Нужно завершённое высшее образование";
        if(w.career.qualification()<job.qualification()||w.career.experienceMonths()<job.experience())return "Недостаточно квалификации или опыта";
        if(w.career.status().equals("BUSINESS"))return "Бизнес и работа по найму не совмещаются";
        if(job.secondary()){
            if(w.management.secondary!=null)return "Подработка уже есть";
            if(w.employed()&&w.management.mainHours==null)return "Нагрузка прежней работы неизвестна; совместимость не определена";
            if((w.employed()?w.management.mainHours:0)+job.hours()>60)return "Суммарная нагрузка превышает 60 часов в неделю";
        }else if(w.management.secondary!=null&&job.hours()+w.management.secondary.hours()>60)return "Несовместимо с текущей подработкой";
        return null;
    }
    static String businessReason(AdultWorld w,BudgetCatalog.Business business){
        String base=blocked(w);if(base!=null)return base;
        if(w.age()<18||!RetirementPolicy.canWork(w))return "Новое дело доступно с 18 лет до пенсии";
        if(w.career.status().equals("BUSINESS"))return "Бизнес уже открыт";
        if(w.management.secondary!=null)return "Сначала завершите подработку";
        if(w.budget.available()<w.cost(business.investmentUnits()))return "Недостаточно средств";
        return null;
    }
    static String raiseReason(AdultWorld w,String ground){
        String base=blocked(w);if(base!=null)return base;
        if(!Set.of("results","tenure","responsibility").contains(ground))return "Неизвестное основание";
        if(!w.career.status().equals("EMPLOYED")||!RetirementPolicy.canWork(w))return "Нужна текущая постоянная работа";
        if(w.month()-w.career.beganMonth()<6)return "Нужен стаж на текущем месте не менее 6 месяцев";
        if(w.management.lastRaise!=null&&w.month()-w.management.lastRaise<6)return "После последнего повышения должно пройти 6 месяцев";
        if(ground.equals("results")&&w.career.satisfaction()<60)return "Недостаточные результаты работы";
        if(ground.equals("tenure")&&w.month()-w.career.beganMonth()<12)return "Для обращения по стажу нужен год на этом месте";
        if(ground.equals("responsibility")&&(w.management.mainHours==null||w.management.mainHours+4+(w.management.secondary==null?0:w.management.secondary.hours())>60))return "Дополнительная нагрузка несовместима с текущей занятостью или неизвестна";
        return null;
    }
    static int raisePercent(String id){return id.equals("tenure")?10:id.equals("responsibility")?20:15;}
    static BudgetOutcome apply(AdultWorld w,String action,String option){
        String base=blocked(w);if(base!=null)throw conflict("BUDGET_ACTION_BLOCKED",base);
        switch(action){
            case "APPLY_JOB", "TAKE_SIDE_JOB" -> {
                var job=BudgetCatalog.job(option);if(job==null||job.secondary()!=action.equals("TAKE_SIDE_JOB"))throw invalid("Unknown job");
                require(jobReason(w,job));
                long pay=w.salary(w.cost(job.payUnits()),job.title());
                if(!job.secondary()&&!roll(w,"job:"+job.id(),.3+w.traits.diligence()/400d+w.career.qualification()*.06))return BudgetOutcome.declined("Работодатель выбрал другого кандидата",w.month());
                w.management.enabled=true;
                if(job.secondary())w.management.secondary=new BudgetState.SideJob(w.id("secondary"),job.id(),job.title(),pay,job.hours(),w.month(),0,null);
                else {
                    CareerSystem.leave(w,"Переход на другую работу");var c=w.career;String id=w.id("employment");var history=new ArrayList<>(c.history());
                    history.add(new Employment(id,job.title(),"Компания «Практика»",w.month(),null,null));
                    w.career=new Career("EMPLOYED",job.title(),"Услуги и производство","Компания «Практика»","Специалист",pay,w.month(),c.experienceMonths(),c.qualification(),65,70,20,c.monthlyPension(),history);
                    w.management.mainHours=job.hours();w.management.business=null;w.management.lastRaise=null;
                }
                return BudgetOutcome.applied("Трудоустройство подтверждено; начисления начнутся за следующие месяцы",w.month(),1,Map.of("basePay",money(pay)));
            }
            case "REQUEST_RAISE" -> {
                require(raiseReason(w,option));
                if(!roll(w,"raise:"+option,.2+w.career.satisfaction()/250d+w.traits.diligence()/400d))return BudgetOutcome.declined("Повышение не согласовано; условия работы сохранены",w.month());
                int percent=raisePercent(option);var c=w.career;w.management.enabled=true;
                CareerSystem.update(w,c.status(),c.monthlyPay()*(100+percent)/100,c.satisfaction(),c.stability(),c.qualification());
                if(option.equals("responsibility"))w.management.mainHours+=4;
                w.management.lastRaise=w.month();
                return BudgetOutcome.applied("Повышение согласовано",w.month(),1,Map.of("basePay",money(w.career.monthlyPay())));
            }
            case "QUIT_JOB" -> {if(!w.employed()||w.career.status().equals("BUSINESS")||!CareerSystem.currentJob(w.career).equals(option))throw conflict("JOB_CHANGED","Текущая работа изменилась");
                CareerSystem.leave(w,"Увольнение по собственному решению");return BudgetOutcome.applied("Работа завершена; другие источники дохода сохранены",w.month(),null,Map.of());}
            case "QUIT_SIDE_JOB" -> {if(w.management.secondary==null||!w.management.secondary.id().equals(option))throw conflict("JOB_CHANGED","Подработка изменилась");
                closeSecondary(w);return BudgetOutcome.applied("Подработка завершена",w.month(),null,Map.of());}
            case "OPEN_BUSINESS" -> {
                var business=BudgetCatalog.business(option);if(business==null)throw invalid("Unknown business");require(businessReason(w,business));
                w.budget=w.budget.postTagged(w.id("business-start"),"Открытие: "+business.title(),0,w.cost(business.investmentUnits()),false,"BUSINESS_START","OTHER",business.id());
                var story=new Story(w.id("business-story"),"business-start","CHOICE","ACTIVE",w.month(),w.month(),null,List.of(),Map.of(),List.of());
                new CareerSystem().act("business",w,new StoryProgress(story)); // One shared career/business state, including story reviews.
                var c=w.career;CareerSystem.update(w,c.status(),w.cost(business.netUnits()),c.satisfaction(),c.stability(),c.qualification());
                w.management.enabled=true;w.management.mainHours=45;w.management.business=new BudgetState.Business(CareerSystem.currentJob(w.career),business.id(),business.title(),w.cost(business.monthlyCostUnits()),w.month());
                w.stories.add(new Story(story.id(),story.template(),"REVIEW","ACTIVE",w.month(),w.month()+6,null,List.of(),Map.of("employment",CareerSystem.currentJob(w.career)),List.of("CHOICE","ANSWER:start")));
                return BudgetOutcome.applied("Бизнес открыт; основная работа прекращена",w.month(),null,Map.of("investment",money(w.cost(business.investmentUnits()))));
            }
            case "SET_STRATEGY" -> {var strategy=BudgetCatalog.strategy(option);if(strategy==null)throw invalid("Unknown strategy");if(!strategy.implemented())throw conflict("STRATEGY_UNAVAILABLE",strategy.description());
                w.management.enabled=true;w.management.strategy=option;return BudgetOutcome.applied("Стратегия сохранена; начислений при выборе нет",w.month(),null,Map.of());}
            case "SELL_ASSET" -> {return AssetSales.sell(w,option);}
            default -> throw invalid("Unknown budget action");
        }
    }
    static void closeSecondary(AdultWorld w){
        var job=w.management.secondary;if(job==null)return;
        w.management.enabled=true;w.management.history.add(new BudgetState.SideJob(job.id(),job.offerId(),job.title(),job.basePay(),job.hours(),job.beganMonth(),job.experienceMonths(),w.month()));w.management.secondary=null;
    }
    private static boolean roll(AdultWorld w,String key,double probability){return new SplittableRandom(w.sim.seed()^((long)w.month()*0x9E3779B97F4A7C15L)^key.hashCode()).nextDouble()<Math.clamp(probability,.01,.95);}
    static void require(String reason){if(reason!=null)throw conflict("BUDGET_ACTION_UNAVAILABLE",reason);}
    static ApiFailure conflict(String code,String reason){return new ApiFailure(HttpStatus.CONFLICT,code,reason);}
    static ApiFailure invalid(String reason){return new ApiFailure(HttpStatus.BAD_REQUEST,"INVALID_BUDGET_ACTION",reason);}
}
