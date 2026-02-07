package com.rorm.ai.swarm;

interface SwarmAgent {

    <T> T call(String input, Class<T> resultType);

}
