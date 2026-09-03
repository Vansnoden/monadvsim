package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.utils.SeedManager;
import com.monadvsim.app.models.utils.SimulationLogger;
import java.util.Random;

/**
 * Temperature‑dependent life‑cycle model for Anopheles stephensi
 * following the ODE differential equations of Komi et al. 2025.
 */
public class LifecycleModel {

    private final SpeciesParameters p;
    private final double dtDays;          
    private final Random rng;             

    public LifecycleModel(SpeciesParameters params, double tickMinutes) {
        this.p = params;
        this.dtDays = tickMinutes / (24.0 * 60.0);
        this.rng = SeedManager.getRandom(); 
        SimulationLogger.info("[Lifecycle] dtDays = %.6f days (tickMinutes = %.1f)", dtDays, tickMinutes);
    }

    private double celsius(double kelvin) { return kelvin - 273.15; }

    public double eggDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double expPart = Math.exp(p.eggDev_rho * T
                - Math.exp(p.eggDev_rho * p.eggDev_k - (p.eggDev_k - T) / p.eggDev_Delta));
        return Math.max(0, expPart + p.eggDev_lambda);
    }

    public double larvaDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        if (T <= p.larvaDev_Tmin || T >= p.larvaDev_Tmax) return 0.0;
        double val = p.larvaDev_a * T * (T - p.larvaDev_Tmin)
                * Math.pow(p.larvaDev_Tmax - T, 1.0 / p.larvaDev_m);
        return Math.max(0, val);
    }

    public double pupaDevelopmentRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double expPart = Math.exp(p.pupaDev_rho * T
                - Math.exp(p.pupaDev_rho * p.pupaDev_k - (p.pupaDev_k - T) / p.pupaDev_Delta));
        return Math.max(0, expPart + p.pupaDev_lambda);
    }

    public double eggMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.eggMort_b1 + p.eggMort_b2 * T + p.eggMort_b3 * T * T);
    }

    public double larvaMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.larvaMort_b1 + p.larvaMort_b2 * T + p.larvaMort_b3 * T * T);
    }

    public double pupaMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        return Math.exp(p.pupaMort_b1 + p.pupaMort_b2 * T + p.pupaMort_b3 * T * T);
    }

    public double adultMortalityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        if (p.adultMort_b1 != 0 || p.adultMort_b2 != 0 || p.adultMort_b3 != 0) {
            return Math.exp(p.adultMort_b1 + p.adultMort_b2 * T + p.adultMort_b3 * T * T);
        }
        return p.adultMortalityPerDay;
    }

    public double fecundityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double val = Math.exp(p.fecundity_rmax + p.fecundity_c * Math.pow(p.fecundity_Topt - T, 2));
        return Math.max(0, val * p.sexRatio);
    }

    public double hostCarryingCapacity(double livestockDensity) {
        return p.hostCarryingCapacityBase + Math.log1p(livestockDensity);
    }

    public double effectiveFecundity(double tempKelvin, double livestockDensity) {
        double F = fecundityRate(tempKelvin);
        double K = hostCarryingCapacity(livestockDensity);
        return F * K * dtDays;
    }

    public double transitionProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    public double mortalityProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

//    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
//        double dL = larvaDevelopmentRate(tempKelvin);
//        double mL = larvaMortalityRate(tempKelvin);
//        double pAdv = transitionProb(dL);
//        double pDie = mortalityProb(mL);
//        
//        if (agent.getAge() % 100 == 0) {
//            SimulationLogger.fine("[DEBUG] Larva age=%d, T=%.2fK, dL=%.6f, pAdv=%.6f, pDie=%.6f",
//                agent.getAge(), tempKelvin, dL, pAdv, pDie);
//        }
//
//        if (rng.nextDouble() < pDie) {
//            agent.setAlive(false);
//            SimulationLogger.info("[DEATH] %s died as LARVA at age %d, temp=%.2fK",
//                    agent.getId(), agent.getAge(), tempKelvin);
//            return;
//        }
//        if (rng.nextDouble() < pAdv) {
//            agent.setStage(LifecycleStage.PUPA);
//            agent.setEnergy(0.6);
//            SimulationLogger.info("[SUCCESS] %s LARVA → PUPA at age %d, temp=%.2fK",
//                    agent.getId(), agent.getAge(), tempKelvin);
//        }
//    }
    
    /**
     * Updates larval development using accumulated progress.
     * This replaces the previous stochastic transition method.
     */
//    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
//        double dL = larvaDevelopmentRate(tempKelvin);
//        double mL = larvaMortalityRate(tempKelvin);
//
//        // 1. Accumulate development progress (in days)
//        double newProgress = agent.getDevelopmentProgress() + dL * dtDays;
//        agent.setDevelopmentProgress(newProgress);
//
//        // Log progress periodically
//        if (agent.getAge() % 100 == 0 && agent.getId().hashCode() % 20 == 0) {
//            SimulationLogger.fine("[LARVA] %s progress=%.4f, dL=%.6f, temp=%.2fK",
//                agent.getId().substring(0, 8), newProgress, dL, tempKelvin);
//        }
//
//        // 2. When accumulated progress reaches 1.0 (100%), pupate
//        if (newProgress >= 1.0) {
//            agent.setStage(LifecycleStage.PUPA);
//            agent.setDevelopmentProgress(0.0);
//            agent.setEnergy(0.6);
//            SimulationLogger.info("[SUCCESS] %s LARVA → PUPA at age %d, temp=%.2fK",
//                    agent.getId().substring(0, 8), agent.getAge(), tempKelvin);
//            return;
//        }
//
//        // 3. Apply mortality with the same probability as before
//        double pDie = mortalityProb(mL);
//        if (rng.nextDouble() < pDie) {
//            agent.setAlive(false);
//            SimulationLogger.info("[DEATH] %s died as LARVA at age %d, temp=%.2fK",
//                    agent.getId().substring(0, 8), agent.getAge(), tempKelvin);
//        }
//    }
    
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double dL = larvaDevelopmentRate(tempKelvin);
        double mL = larvaMortalityRate(tempKelvin);

        double newProgress = agent.getDevelopmentProgress() + dL * dtDays;
        agent.setDevelopmentProgress(newProgress);

        if (agent.getAge() % 100 == 0 && agent.getId().hashCode() % 20 == 0) {
            SimulationLogger.fine("[LARVA] %s progress=%.4f, dL=%.6f, temp=%.2fK",
                agent.getId().substring(0, 8), newProgress, dL, tempKelvin);
        }

        if (newProgress >= 1.0) {
            agent.setStage(LifecycleStage.PUPA);
            agent.setDevelopmentProgress(0.0);  // Reset progress
            agent.setEnergy(0.6);
            SimulationLogger.info("[SUCCESS] %s LARVA → PUPA at age %d, temp=%.2fK",
                agent.getId().substring(0, 8), agent.getAge(), tempKelvin);
            return;
        }

        double pDie = mortalityProb(mL);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as LARVA at age %d, temp=%.2fK",
                agent.getId().substring(0, 8), agent.getAge(), tempKelvin);
        }
    }

    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double dP = pupaDevelopmentRate(tempKelvin);
        double mP = pupaMortalityRate(tempKelvin);
        double pAdv = transitionProb(dP);
        double pDie = mortalityProb(mP);

        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as PUPA at age %d, temp=%.2fK",
                    agent.getId(), agent.getAge(), tempKelvin);
            return;
        }
        if (rng.nextDouble() < pAdv) {
            agent.setStage(LifecycleStage.ADULT);
            agent.setEnergy(0.9);
            SimulationLogger.info("[SUCCESS] %s PUPA → ADULT at age %d, temp=%.2fK",
                    agent.getId(), agent.getAge(), tempKelvin);
        }
    }

    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double mA = adultMortalityRate(tempKelvin);
        double pDie = mortalityProb(mA);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            SimulationLogger.info("[DEATH] %s died as ADULT at age %d, temp=%.2fK",
                    agent.getId(), agent.getAge(), tempKelvin);
        }
    }

    public int tryHatchEggs(int currentEggs, double tempKelvin) {
        if (currentEggs <= 0) return 0;
        double de = eggDevelopmentRate(tempKelvin);
        double me = eggMortalityRate(tempKelvin);
        double pAdv = transitionProb(de);
        double pDie = mortalityProb(me);

        int deaths;
        if (currentEggs < 100) {
            deaths = 0;
            for (int i = 0; i < currentEggs; i++) {
                if (rng.nextDouble() < pDie) deaths++;
            }
        } else {
            deaths = (int) Math.round(currentEggs * pDie);
        }
        int survivors = currentEggs - deaths;

        int hatched;
        if (survivors < 100) {
            hatched = 0;
            for (int i = 0; i < survivors; i++) {
                if (rng.nextDouble() < pAdv) hatched++;
            }
        } else {
            hatched = (int) Math.round(survivors * pAdv);
        }
        return hatched;
    }

    public int eggsToLay(double tempKelvin, double hostDensity) {
        double expected = effectiveFecundity(tempKelvin, hostDensity);
        int eggs = (int) expected;
        if (rng.nextDouble() < (expected - eggs)) eggs++;
        return Math.min(eggs, 50);
    }
}