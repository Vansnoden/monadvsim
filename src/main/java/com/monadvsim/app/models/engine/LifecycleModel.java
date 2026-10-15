package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.InertAgent;
import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.utils.SeedManager;
import com.monadvsim.app.models.utils.SimulationLogger;
import java.util.Random;

/**
 * Temperature‑dependent life‑cycle model for Anopheles stephensi
 * following the ODE differential equations of Komi et al. 2025.
 *
 * <p>Two responsibilities are handled here:
 * <ol>
 *   <li>Temperature response surfaces for development, mortality, and
 *       fecundity.</li>
 *   <li>Per-tick stage progression, including the bookkeeping that keeps
 *       the parent {@link InertAgent}'s larval counter aligned with the
 *       number of live {@link LivingAgent} larvae it has spawned.</li>
 * </ol>
 *
 * <p>The {@code larvalCount} on a tank is a live count, not a cumulative
 * hatched count. It is incremented by {@code InertAgent.hatchEggs} when
 * eggs hatch, and decremented here when a larva either pupates or dies.
 * Without this, the tank's counter would saturate at
 * {@code max_larvae_capacity} and hatching would stop permanently.
 */
public class LifecycleModel {

    private final SpeciesParameters p;
    private final double dtDays;
    private final Random rng;

    /**
     * Registry handle for looking up the parent tank by id.
     * Needed because {@link LivingAgent} only carries the parent tank's id
     * (a String), not a direct reference, so that snapshots stay serializable.
     */
    private final SpatialRegistry spatialRegistry;

    private static final double T_BASE_LARVA = 13.3;   // °C
    private static final double T_BASE_PUPA  = 10.0;   // °C
    private static final double DEGREE_DAYS_LARVA_TO_PUPA = 66.5;
    private static final double DEGREE_DAYS_PUPA_TO_ADULT = 22.0;

    public LifecycleModel(SpeciesParameters params, double tickMinutes,
                          SpatialRegistry spatialRegistry) {
        this.p = params;
        this.dtDays = tickMinutes / (24.0 * 60.0);
        this.rng = SeedManager.getRandom();
        this.spatialRegistry = spatialRegistry;
        SimulationLogger.info("[Lifecycle] dtDays = %.6f days (tickMinutes = %.1f)",
                dtDays, tickMinutes);
    }

    private double celsius(double kelvin) { return kelvin - 273.15; }

    // ========================================================================
    // Temperature-response surfaces
    // ========================================================================

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

    /**
     * Raw temperature response of fecundity, in "eggs per female per day"
     * BEFORE sex ratio and host-density scaling. Gaussian-on-log form
     * following Komi et al. 2025.
     */
    public double fecundityRate(double tempKelvin) {
        double T = celsius(tempKelvin);
        double exponent = p.fecundity_rmax + p.fecundity_c * Math.pow(p.fecundity_Topt - T, 2);
        return p.fecundityScale * Math.max(0.0, Math.exp(exponent));
    }

    /**
     * Density-dependent host-availability multiplier on fecundity.
     * Uses log1p so an empty landscape still has a small non-zero factor.
     */
    public double hostCarryingCapacity(double hostDensity) {
        double host = Math.max(0.0, hostDensity);
        return p.hostCarryingCapacityBase + Math.log1p(host);
    }

    /**
     * Expected eggs per female per tick. This is the quantity the Poisson
     * sampler consumes; it is the full product of every dimensioned factor
     * so the calibration knob {@link SpeciesParameters#fecundityScale}
     * has a single, predictable effect.
     */
    public double effectiveFecundity(double tempKelvin, double hostDensity) {
        return fecundityRate(tempKelvin)
                * p.sexRatio
                * hostCarryingCapacity(hostDensity)
                * dtDays;
    }

    public double transitionProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    public double mortalityProb(double ratePerDay) {
        return 1.0 - Math.exp(-ratePerDay * dtDays);
    }

    // ========================================================================
    // Stage transitions
    // ========================================================================

    /**
     * Advance a larva by one tick.
     *
     * <p>On pupation, the parent tank's live larval count is decremented
     * so that the tank can host a subsequent cohort.
     */
    public void tryAdvanceFromLarva(LivingAgent agent, double tempKelvin) {
        double tempC = celsius(tempKelvin);
        double mL = larvaMortalityRate(tempKelvin);

        // 1. Accumulate degree-days
        if (tempC > T_BASE_LARVA) {
            double dd = (tempC - T_BASE_LARVA) * dtDays;
            agent.setAccumulatedDegreeDays(agent.getAccumulatedDegreeDays() + dd);
        }

        // 2. Pupation
        if (agent.getAccumulatedDegreeDays() >= DEGREE_DAYS_LARVA_TO_PUPA) {
            agent.setStage(LifecycleStage.PUPA);
            agent.setAccumulatedDegreeDays(0.0);
            agent.setStageAgeDays(0.0);
            agent.setStageAgeTicks(0);
            agent.setDevelopmentProgress(0.0);
            agent.setEnergy(0.6);

            decrementParentTank(agent);

//            SimulationLogger.info(
//                "[SUCCESS] %s LARVA → PUPA at stage age %d, DD=%.2f, temp=%.2fC",
//                agent.getId().substring(0, 8),
//                agent.getStageAgeTicks(),
//                DEGREE_DAYS_LARVA_TO_PUPA,
//                tempC);
//            return;
        }

        // 3. Mortality
        double pDie = mortalityProb(mL);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
            decrementParentTank(agent);
//            SimulationLogger.info(
//                "[DEATH] %s died as LARVA at stage age %d, temp=%.2fC",
//                agent.getId().substring(0, 8),
//                agent.getStageAgeTicks(),
//                tempC);
        }
    }

    /**
     * Advance a pupa by one tick. On emergence the agent leaves the
     * aquatic environment, so no tank counter is involved.
     */
    public void tryAdvanceFromPupa(LivingAgent agent, double tempKelvin) {
        double tempC = celsius(tempKelvin);
        double mP = pupaMortalityRate(tempKelvin);

        // 1. Accumulate degree-days
        if (tempC > T_BASE_PUPA) {
            double dd = (tempC - T_BASE_PUPA) * dtDays;
            agent.setAccumulatedDegreeDays(agent.getAccumulatedDegreeDays() + dd);
        }

        // 2. Emergence
        if (agent.getAccumulatedDegreeDays() >= DEGREE_DAYS_PUPA_TO_ADULT) {
            agent.setStage(LifecycleStage.ADULT);
            agent.setAccumulatedDegreeDays(0.0);
            agent.setStageAgeDays(0.0);
            agent.setStageAgeTicks(0);
            agent.setEnergy(0.9);

//            SimulationLogger.info(
//                "[SUCCESS] %s PUPA → ADULT at stage age %d, DD=%.2f, temp=%.2fC",
//                agent.getId().substring(0, 8),
//                agent.getStageAgeTicks(),
//                DEGREE_DAYS_PUPA_TO_ADULT,
//                tempC);
            return;
        }

        // 3. Mortality
        double pDie = mortalityProb(mP);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
//            SimulationLogger.info(
//                "[DEATH] %s died as PUPA at stage age %d, temp=%.2fC",
//                agent.getId().substring(0, 8),
//                agent.getStageAgeTicks(),
//                tempC);
        }
    }

    public void applyAdultMortality(LivingAgent agent, double tempKelvin) {
        double mA = adultMortalityRate(tempKelvin);
        double pDie = mortalityProb(mA);
        if (rng.nextDouble() < pDie) {
            agent.setAlive(false);
//            SimulationLogger.info(
//                "[DEATH] %s died as ADULT at age %d, temp=%.2fC",
//                agent.getId(), agent.getAge(), celsius(tempKelvin));
        }
    }

    // ========================================================================
    // Egg hatching
    // ========================================================================

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

    /**
     * Poisson sample of the expected egg count per tick, capped at the
     * biological maximum per laying event.
     *
     * <p>Knuth's algorithm is exact for λ ≲ 30 (which covers our range)
     * and O(λ) — negligible next to everything else the tick does.
     * Falls back to a normal approximation above that.
     */
    public int eggsToLay(double tempKelvin, double hostDensity) {
        double lambda = effectiveFecundity(tempKelvin, hostDensity);
        if (lambda <= 0.0) return 0;
        int eggs = poisson(lambda);
        return Math.min(eggs, 50);
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /**
     * Decrement the live larval count on this larva's parent tank, if the
     * larva was spawned from a tank (initial-seeded larvae have no parent).
     *
     * <p>Safe no-op if the registry is null (e.g. unit tests), if the
     * parent tank has already been removed from the world, or if the
     * lookup fails for any reason.
     */
    private void decrementParentTank(LivingAgent agent) {
        String parentId = agent.getParentTankId();
        if (parentId == null || spatialRegistry == null) return;
        Agent parent = spatialRegistry.getAgentById(parentId);
        if (parent instanceof InertAgent tank) {
            tank.decrementLarvalCount(1);
        }
    }

    /**
     * Poisson sample using Knuth's algorithm (λ < 30) or a rounded normal
     * approximation (λ ≥ 30). Uses the shared LifecycleModel RNG so the
     * global seed still governs repeatability.
     */
    private int poisson(double lambda) {
        if (lambda >= 30.0) {
            double g = rng.nextGaussian() * Math.sqrt(lambda) + lambda;
            return Math.max(0, (int) Math.round(g));
        }
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > L);
        return k - 1;
    }
}