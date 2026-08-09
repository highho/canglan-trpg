package com.canglan.world.ally;

/** 招募结果。对应 C# RecruitmentResult。 */
public record RecruitmentResult(boolean success, String message) {

    public static RecruitmentResult ok(String msg) { return new RecruitmentResult(true, msg); }
    public static RecruitmentResult fail(String msg) { return new RecruitmentResult(false, msg); }
}
