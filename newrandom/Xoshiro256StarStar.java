/*
 * Copyright (c) 2016, 2019, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

// package java.util;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A generator of uniform pseudorandom values applicable for use in
 * (among other contexts) isolated parallel computations that may
 * generate subtasks.  Class {@code Xoshiro256StarStar} implements
 * interfaces {@link java.util.Rng} and {@link java.util.LeapableRng},
 * and therefore supports methods for producing pseudorandomly chosen
 * numbers of type {@code int}, {@code long}, {@code float}, and {@code double}
 * as well as creating new {@code Xoshiro256StarStar} objects
 * by "jumping" or "leaping".
 *
 * <p>Series of generated values pass the TestU01 BigCrush and PractRand test suites
 * that measure independence and uniformity properties of random number generators.
 * (Most recently validated with
 * <a href="http://simul.iro.umontreal.ca/testu01/tu01.html">version 1.2.3 of TestU01</a>
 * and <a href="http://pracrand.sourceforge.net">version 0.90 of PractRand</a>.
 * Note that TestU01 BigCrush was used to test not only values produced by the {@code nextLong()}
 * method but also the result of bit-reversing each value produced by {@code nextLong()}.)
 * These tests validate only the methods for certain
 * types and ranges, but similar properties are expected to hold, at
 * least approximately, for others as well.
 *
 * <p>The class {@code Xoshiro256StarStar} uses the {@code xoshiro256} algorithm,
 * version 1.0 (parameters 17, 45), with the "**" scrambler (a mixing function).
 * Its state consists of four {@code long} fields {@code x0}, {@code x1}, {@code x2},
 * and {@code x3}, which can take on any values provided that they are not all zero.
 * The period of this generator is 2<sup>256</sup>-1.
 *
 * <p>The 64-bit values produced by the {@code nextLong()} method are equidistributed.
 * To be precise, over the course of the cycle of length 2<sup>256</sup>-1,
 * each nonzero {@code long} value is generated 2<sup>192</sup> times,
 * but the value 0 is generated only 2<sup>192</sup>-1 times.
 * The values produced by the {@code nextInt()}, {@code nextFloat()}, and {@code nextDouble()}
 * methods are likewise equidistributed.
 *
 * <p>In fact, the 64-bit values produced by the {@code nextLong()} method are 4-equidistributed.
 * To be precise: consider the (overlapping) length-4 subsequences of the cycle of 64-bit
 * values produced by {@code nextLong()} (assuming no other methods are called that would
 * affect the state).  There are 2<sup>256</sup>-1 such subsequences, and each subsequence,
 * which consists of 4 64-bit values, can have one of 2<sup>256</sup> values.  Of those
 * 2<sup>256</sup> subsequence values, each one is generated exactly once over the course
 * of the entire cycle, except that the subsequence (0, 0, 0, 0) never appears.
 * The values produced by the {@code nextInt()}, {@code nextFloat()}, and {@code nextDouble()}
 * methods are likewise 4-equidistributed, but note that that the subsequence (0, 0, 0, 0)
 * can also appear (but occurring somewhat less frequently than all other subsequences),
 * because the values produced by those methods have fewer than 64 randomly chosen bits.
 *
 * <p>Instances {@code Xoshiro256StarStar} are <em>not</em> thread-safe.
 * They are designed to be used so that each thread as its own instance.
 * The methods {@link #jump} and {@link #leap} and {@link #jumps} and {@link #leaps}
 * can be used to construct new instances of {@code Xoshiro256StarStar} that traverse
 * other parts of the state cycle.
 *
 * <p>Instances of {@code Xoshiro256StarStar} are not cryptographically
 * secure.  Consider instead using {@link java.security.SecureRandom}
 * in security-sensitive applications. Additionally,
 * default-constructed instances do not use a cryptographically random
 * seed unless the {@linkplain System#getProperty system property}
 * {@code java.util.secureRandomSeed} is set to {@code true}.
 *
 * @author  Guy Steele
 * @since   1.9
 */
public final class Xoshiro256StarStar implements LeapableRng {

    /*
     * Implementation Overview.
     *
     * This is an implementation of the xoroshiro128** algorithm written
     * in 2018 by David Blackman and Sebastiano Vigna (vigna@acm.org).
     * See http://xoshiro.di.unimi.it and these two papers:
     *
     *    Sebastiano Vigna. 2016. An Experimental Exploration of Marsaglia's
     *    xorshift Generators, Scrambled. ACM Transactions on Mathematical
     *    Software 42, 4, Article 30 (June 2016), 23 pages.
     *    https://doi.org/10.1145/2845077
     *
     *    David Blackman and Sebastiano Vigna.  2018.  Scrambled Linear
     *    Pseudorandom Number Generators.  Computing Research Repository (CoRR).
     *    http://arxiv.org/abs/1805.01407
     *
     * The jump operation moves the current generator forward by 2*128
     * steps; this has the same effect as calling nextLong() 2**128
     * times, but is much faster.  Similarly, the leap operation moves
     * the current generator forward by 2*192 steps; this has the same
     * effect as calling nextLong() 2**192 times, but is much faster.
     * The copy method may be used to make a copy of the current
     * generator.  Thus one may repeatedly and cumulatively copy and
     * jump to produce a sequence of generators whose states are well
     * spaced apart along the overall state cycle (indeed, the jumps()
     * and leaps() methods each produce a stream of such generators).
     * The generators can then be parceled out to other threads.
     *
     * File organization: First static fields, then instance
     * fields, then constructors, then instance methods.
     */

    /* ---------------- static fields ---------------- */

    /**
     * The seed generator for default constructors.
     */
    private static final AtomicLong defaultGen = new AtomicLong(RngSupport.initialSeed());

    /*
     * The period of this generator, which is 2**256 - 1.
     */
    private static final BigInteger thePeriod =
	BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    /* ---------------- instance fields ---------------- */

    /**
     * The per-instance state.
     * At least one of the four fields x0, x1, x2, and x3 must be nonzero.
     */
    private long x0, x1, x2, x3;

    /* ---------------- constructors ---------------- */

    /**
     * Basic constructor that initializes all fields from parameters.
     * It then adjusts the field values if necessary to ensure that
     * all constraints on the values of fields are met.
     */
    public Xoshiro256StarStar(long x0, long x1, long x2, long x3) {
	this.x0 = x0;
        this.x1 = x1;
        this.x2 = x2;
        this.x3 = x3;
	// If x0, x1, x2, and x3 are all zero, we must choose nonzero values.
        if ((x0 | x1 | x2 | x3) == 0) {
	    // At least three of the four values generated here will be nonzero.
	    this.x0 = RngSupport.mixStafford13(x0 += RngSupport.GOLDEN_RATIO_64);
	    this.x1 = (x0 += RngSupport.GOLDEN_RATIO_64);
	    this.x2 = (x0 += RngSupport.GOLDEN_RATIO_64);
	    this.x3 = (x0 += RngSupport.GOLDEN_RATIO_64);
	}
    }

    /**
     * Creates a new instance of {@code Xoshiro256StarStar} using the
     * specified {@code long} value as the initial seed. Instances of
     * {@code Xoshiro256StarStar} created with the same seed in the same
     * program generate identical sequences of values.
     *
     * @param seed the initial seed
     */
    public Xoshiro256StarStar(long seed) {
	// Using a value with irregularly spaced 1-bits to xor the seed
	// argument tends to improve "pedestrian" seeds such as 0 or
	// other small integers.  We may as well use SILVER_RATIO_64.
	//
	// The x values are then filled in as if by a SplitMix PRNG with
	// GOLDEN_RATIO_64 as the gamma value and Stafford13 as the mixer.
        this(RngSupport.mixStafford13(seed ^= RngSupport.SILVER_RATIO_64),
	     RngSupport.mixStafford13(seed += RngSupport.GOLDEN_RATIO_64),
	     RngSupport.mixStafford13(seed += RngSupport.GOLDEN_RATIO_64),
	     RngSupport.mixStafford13(seed + RngSupport.GOLDEN_RATIO_64));
    }

    /**
     * Creates a new instance of {@code Xoshiro256StarStar} that is likely to
     * generate sequences of values that are statistically independent
     * of those of any other instances in the current program execution,
     * but may, and typically does, vary across program invocations.
     */
    public Xoshiro256StarStar() {
	// Using GOLDEN_RATIO_64 here gives us a good Weyl sequence of values.
        this(defaultGen.getAndAdd(RngSupport.GOLDEN_RATIO_64));
    }

    /**
     * Creates a new instance of {@code Xoshiro256StarStar} using the specified array of
     * initial seed bytes. Instances of {@code Xoshiro256StarStar} created with the same
     * seed array in the same program execution generate identical sequences of values.
     *
     * @param seed the initial seed
     */
    public Xoshiro256StarStar(byte[] seed) {
	// Convert the seed to 4 long values, which are not all zero.
	long[] data = RngSupport.convertSeedBytesToLongs(seed, 4, 4);
	long x0 = data[0], x1 = data[1], x2 = data[2], x3 = data[3];
        this.x0 = x0;
        this.x1 = x1;
        this.x2 = x2;
        this.x3 = x3;
    }

    /* ---------------- public methods ---------------- */

    public Xoshiro256StarStar copy() { return new Xoshiro256StarStar(x0, x1, x2, x3); }

    /**
     * Returns a pseudorandom {@code long} value.
     *
     * @return a pseudorandom {@code long} value
     */

    public long nextLong() {
	final long z = x0;
	long q0 = x0, q1 = x1, q2 = x2, q3 = x3;	
	{ long t = q1 << 17; q2 ^= q0; q3 ^= q1; q1 ^= q2; q0 ^= q3; q2 ^= t; q3 = Long.rotateLeft(q3, 45); }  // xoshiro256 1.0
	x0 = q0; x1 = q1; x2 = q2; x3 = q3;
	return Long.rotateLeft(z * 5, 7) * 9;  // "starstar" mixing function
    }

    public BigInteger period() { return thePeriod; }

    
    public double defaultJumpDistance() { return 0x1.0p64; }
    public double defaultLeapDistance() { return 0x1.0p96; }

    private static final long[] JUMP_TABLE = {
	0x180ec6d33cfd0abaL, 0xd5a61266f0c9392cL, 0xa9582618e03fc9aaL, 0x39abdc4529b1661cL };
    
    private static final long[] LEAP_TABLE = {
	0x76e15d3efefdcbbfL, 0xc5004e441c522fb3L, 0x77710069854ee241L, 0x39109bb02acbe635L };
   
/* This is the jump function for the generator. It is equivalent
   to 2**128 calls to next(); it can be used to generate 2**128
   non-overlapping subsequences for parallel computations. */

    public void jump() { jumpAlgorithm(JUMP_TABLE); }
    
/* This is the long-jump function for the generator. It is equivalent to
   2**192 calls to next(); it can be used to generate 2**64 starting points,
   from each of which jump() will generate 2**64 non-overlapping
   subsequences for parallel distributed computations. */

    public void leap() { jumpAlgorithm(LEAP_TABLE); }

    private void jumpAlgorithm(long[] table) {
	long s0 = 0, s1 = 0, s2 = 0, s3 = 0;
	for (int i = 0; i < table.length; i++) {
	    for (int b = 0; b < 64; b++) {
		if ((table[i] & (1L << b)) != 0) {
		    s0 ^= x0;
		    s1 ^= x1;
		    s2 ^= x2;
		    s3 ^= x3;
		}
		nextLong();
	    }
	    x0 = s0;
	    x1 = s1;
	    x2 = s2;
	    x3 = s3;
	}
    }

}
