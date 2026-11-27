package com.life.life_backend.economy;

import java.util.List;

/** Machine keys only. Legacy descriptions are never parsed as accounting data. */
public record CashTags(String sourceId,String categoryId,String relatedId) {
    public static CashTags infer(String id,String kind) {
        if(id.startsWith("salary-"))return new CashTags("LEGACY_EARNINGS","OTHER",null);
        if(id.startsWith("pension-"))return new CashTags("PENSION","OTHER",null);
        if(id.startsWith("partner-"))return new CashTags("PARTNER_CONTRIBUTION","OTHER",null);
        if(id.startsWith("interest-"))return new CashTags("SAVINGS_RETURN","OTHER",null);
        if(id.startsWith("living-housing-"))return new CashTags("HOUSING","HOUSING",null);
        if(id.startsWith("living-food-"))return new CashTags("FOOD","FOOD_SHOPPING",null);
        if(id.startsWith("living-"))return new CashTags("LEGACY_MIXED_LIVING","OTHER",null);
        if(id.startsWith("children-"))return new CashTags("CHILD_SUPPORT","CHILDREN_EDUCATION",null);
        if(id.startsWith("treatment-"))return new CashTags("TREATMENT","HEALTH",null);
        if(id.startsWith("loan-interest-"))return new CashTags("DEBT_INTEREST","OTHER",suffix(id,"loan-interest-"));
        if(id.startsWith("loan-principal-"))return new CashTags("DEBT_PRINCIPAL","OTHER",suffix(id,"loan-principal-"));
        if(id.startsWith("budget-"))return new CashTags("ALLOWANCE","FOOD_SHOPPING",null);
        return new CashTags("LEGACY_UNCLASSIFIED","OTHER",null);
    }
    private static String suffix(String id,String prefix){return id.substring(prefix.length(),id.lastIndexOf('-'));}
    public static CashTags forPurchase(LocalBudget.Purchase p,boolean upkeep){
        String category=switch(p.categoryId()){
            case "property"->"PROPERTY";case "pets"->"PETS";case "education"->"CHILDREN_EDUCATION";
            case "health","procedures"->"HEALTH";case "relationships","hobby"->"LEISURE_GIFTS";
            case "city","country"->"HOUSING";default->"FOOD_SHOPPING";};
        return new CashTags(upkeep?"ASSET_UPKEEP":"PURCHASE",category,p.id().toString());
    }
    public static CashTags resolve(LocalBudget.Entry entry,List<LocalBudget.Purchase> purchases){
        if(entry.sourceId()!=null)return new CashTags(entry.sourceId(),entry.categoryId(),entry.relatedId());
        for(var p:purchases)if(entry.id().equals(p.id().toString())||entry.id().startsWith("upkeep-"+p.id()+"-"))return forPurchase(p,entry.kind().equals("UPKEEP"));
        return infer(entry.id(),entry.kind());
    }
}
