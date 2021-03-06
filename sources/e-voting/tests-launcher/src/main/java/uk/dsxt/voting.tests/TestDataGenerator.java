/******************************************************************************
 * e-voting system                                                            *
 * Copyright (C) 2016 DSX Technologies Limited.                               *
 * *
 * This program is free software; you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation; either version 2 of the License, or          *
 * (at your option) any later version.                                        *
 * *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied                         *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * *
 * You can find copy of the GNU General Public License in LICENSE.txt file    *
 * at the top-level directory of this distribution.                           *
 * *
 * Removal or modification of this copyright notice is prohibited.            *
 * *
 ******************************************************************************/

package uk.dsxt.voting.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.Value;
import lombok.experimental.NonFinal;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import uk.dsxt.voting.client.datamodel.ClientCredentials;
import uk.dsxt.voting.client.datamodel.ClientsOnTime;
import uk.dsxt.voting.common.domain.dataModel.*;
import uk.dsxt.voting.common.iso20022.Iso20022Serializer;
import uk.dsxt.voting.common.utils.crypto.CryptoHelperImpl;
import uk.dsxt.voting.common.utils.crypto.KeyPair;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Log4j2
public class TestDataGenerator {
    private final static ObjectMapper mapper = new ObjectMapper();

    private final static String SECURITY = "security";
    private final static String MASTER_PASSWORD = "master_password";

    private final static CryptoHelperImpl cryptoHelper = CryptoHelperImpl.DEFAULT_CRYPTO_HELPER;

    @Value
    @AllArgsConstructor
    class ClientFullInfo {
        Map<String, BigDecimal> packetSizeBySecurity;
        int id;
        int holderId;
        ParticipantRole role;
        String privateKey;
        String publicKey;
        String name;
        VoteResult vote;
        List<ClientFullInfo> clients;
        @NonFinal
        @Setter
        boolean isVictim;
        @NonFinal
        @Setter
        boolean isHonest;
        @NonFinal
        @Setter
        String walletOffShedule;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1) {
                generateCredentialsJSON();
                return;
            }
            if (args.length > 0 && args.length < 10) {
                System.out.println("<name> <totalParticipant> <holdersCount> <vmCount> <levelsCount> <minutes> <generateVotes> <victimsCount>");
                throw new IllegalArgumentException("Invalid arguments count exception.");
            }
            int argId = 0;
            String name = args.length == 0 ? "ss_10_100000_30" : args[argId++];
            int totalParticipant = args.length == 0 ? 100000 : Integer.parseInt(args[argId++]);
            int holdersCount = args.length == 0 ? 10 : Integer.parseInt(args[argId++]);
            int vmCount = args.length == 0 ? 1 : Integer.parseInt(args[argId++]);
            int levelsCount = args.length == 0 ? 3 : Integer.parseInt(args[argId++]);
            int minutes = args.length == 0 ? 30 : Integer.parseInt(args[argId++]);
            boolean generateVotes = args.length == 0 ? true : Boolean.parseBoolean(args[argId++]);
            int victimsCount = args.length == 0 ? 0 : Integer.parseInt(args[argId++]);
            boolean generateDisconnect = args.length == 0 ? false : Boolean.parseBoolean(args[argId++]);
            int disconnectNodes = args.length == 0 ? 0 : Integer.parseInt(args[argId]);
            TestDataGenerator generator = new TestDataGenerator();
            generator.generate(name, totalParticipant, holdersCount, vmCount, levelsCount, minutes, generateVotes, victimsCount, generateDisconnect, disconnectNodes);
        } catch (Exception e) {
            log.error("Test generation was failed.", e);
        }
    }

    private void generate(String name, int totalParticipant, int holdersCount, int vmCount, int levelsCount, int minutes, boolean generateVotes, int victimsCount,
                          boolean generateDisconnect, int disconnectNodes) throws Exception {
        ClientFullInfo[] clients = new ClientFullInfo[totalParticipant];
        Participant[] participants = new Participant[totalParticipant];
        //generating keys
        long start = System.currentTimeMillis();
        log.debug("generating {} keys", totalParticipant);
        KeyPair[] holderKeys = cryptoHelper.createCryptoKeysGenerator().generateKeys(holdersCount + 1);
        KeyPair[] keys = new KeyPair[totalParticipant];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = holderKeys[Math.min(i, holderKeys.length - 1)];
        }
        log.debug("{} keys generated. {} seconds spent", totalParticipant, (System.currentTimeMillis() - start) / 1000);

        //generating voting
        long now = System.currentTimeMillis();
        long dayStart = now - now % (24 * 60 * 60 * 1000);
        Voting voting = generateVotingEn(dayStart, dayStart + minutes * 60000);
        //generating participants info
        for (int i = 0; i < totalParticipant; i++) {
            ParticipantRole role;
            if (i == 0)
                role = ParticipantRole.NRD;
            else if (i < holdersCount)
                role = ParticipantRole.NominalHolder;
            else
                role = ParticipantRole.Owner;
            HashMap<String, BigDecimal> securities = new HashMap<>();
            securities.put(SECURITY, role == ParticipantRole.Owner ? new BigDecimal(randomInt(15, 100)) : BigDecimal.ZERO);
            int ownerIdx = role == ParticipantRole.NRD ? -1 : i < 6 ? 0 : randomInt(0, Math.min(i, holdersCount) - 1);
            VoteResult vote = role != ParticipantRole.Owner ? null : generateVote(Integer.toString(i), securities, voting);
            clients[i] = new ClientFullInfo(securities, i, ownerIdx, role, keys[i].getPrivateKey(), keys[i].getPublicKey(), String.format("Random name #%d", i), vote, new ArrayList<>(), false, true, "");
            participants[i] = new Participant(i == 0 ? "00" : Integer.toString(i), clients[i].getName(), clients[i].getPublicKey());
            if (role != ParticipantRole.NRD) {
                clients[ownerIdx].clients.add(clients[i]);
                for (; ownerIdx >= 0; ownerIdx = clients[ownerIdx].getHolderId()) {
                    for (Map.Entry<String, BigDecimal> secEntry : securities.entrySet()) {
                        clients[ownerIdx].getPacketSizeBySecurity().put(secEntry.getKey(), clients[ownerIdx].getPacketSizeBySecurity().get(secEntry.getKey()).add(secEntry.getValue()));
                    }
                }
            }
        }

        if (victimsCount > 0) {
            ThreadLocalRandom.current().ints(1, holdersCount - 1).distinct().limit(victimsCount).forEach(i -> clients[i].setVictim(true));
            ThreadLocalRandom.current().ints(1, holdersCount - 1).filter(i -> !clients[i].isVictim()).distinct().limit(victimsCount).forEach(i -> clients[i].setHonest(false));
        }

        saveData(clients, participants, name, voting, holdersCount, vmCount, minutes, generateVotes, generateDisconnect, disconnectNodes);
    }

    private static void generateCredentialsJSON() throws IOException {
        List<ClientCredentials> credentials = new ArrayList<>();
        credentials.add(new ClientCredentials("user1", "1234"));
        credentials.add(new ClientCredentials("user2", "1234"));
        credentials.add(new ClientCredentials("user3", "1234"));
        credentials.add(new ClientCredentials("user4", "1234"));
        credentials.add(new ClientCredentials("user5", "1234"));
        credentials.add(new ClientCredentials("user6", "1234"));
        credentials.add(new ClientCredentials("user7", "1234"));
        credentials.add(new ClientCredentials("user8", "1234"));
        credentials.add(new ClientCredentials("user9", "1234"));
        credentials.add(new ClientCredentials("user10", "1234"));
        final String string = mapper.writeValueAsString(credentials);
        FileUtils.writeStringToFile(new File("credentials00.json"), string);
    }

    private static VoteResult generateVote(String id, HashMap<String, BigDecimal> securities, Voting voting) {
        VoteResult vote = new VoteResult(voting.getId(), id, securities.get(SECURITY));
        for (int j = 0; j < voting.getQuestions().length; j++) {
            String questionId = voting.getQuestions()[j].getId();

            if (voting.getQuestions()[j].isCanSelectMultiple()) {
                BigDecimal totalSum = BigDecimal.ZERO;
                for (int i = 0; i < voting.getQuestions()[j].getAnswers().length; i++) {
                    String answerId = voting.getQuestions()[j].getAnswers()[i].getId();
                    int amount = randomInt(0, vote.getPacketSize().subtract(totalSum).intValue());
                    BigDecimal voteAmount = new BigDecimal(amount);
                    totalSum = totalSum.add(voteAmount);
                    if (voteAmount.compareTo(BigDecimal.ZERO) > 0)
                        vote.setAnswer(questionId, answerId, voteAmount);
                }
            } else {
                String answerId = voting.getQuestions()[j].getAnswers()[randomInt(0, voting.getQuestions()[j].getAnswers().length - 1)].getId();
                BigDecimal voteAmount = new BigDecimal(randomInt(0, vote.getPacketSize().intValue()));
                if (voteAmount.compareTo(BigDecimal.ZERO) > 0)
                    vote.setAnswer(questionId, answerId, voteAmount);
            }
        }
        return vote;
    }

    private static Voting generateVotingRu(long startTime, long endTime) throws Exception {
        Question[] questions = new Question[5];
        Answer[] answers = new Answer[3];
        answers[0] = new Answer("1", "Да");
        answers[1] = new Answer("2", "Нет");
        answers[2] = new Answer("3", "Воздержался");
        questions[0] = new Question("1.1", "Выбрать в Ревизионную комиссию Общества B", answers);
        answers = new Answer[3];
        answers[0] = new Answer("1", "Да");
        answers[1] = new Answer("2", "Нет");
        answers[2] = new Answer("3", "Воздержался");
        questions[1] = new Question("1.2", "Выбрать в Ревизионную комиссию Общества A", answers);
        answers = new Answer[3];
        answers[0] = new Answer("1", "Да");
        answers[1] = new Answer("2", "Нет");
        answers[2] = new Answer("3", "Воздержался");
        questions[2] = new Question("2.1", "Избрать Совет директоров Общества. (Примечание: количественный состав Совета директоров в соответствии с Уставом – 3)", answers);
        answers = new Answer[3];
        answers[0] = new Answer("2.1.1", "Иванов");
        answers[1] = new Answer("2.1.2", "Петров");
        answers[2] = new Answer("2.1.3", "Сидоров");
        questions[3] = new Question("2.1.multi", "Избрать Совет директоров Общества. (Примечание: количественный состав Совета директоров в соответствии с Уставом – 3)", answers, true, 1);
        answers = new Answer[3];
        answers[0] = new Answer("1", "Да");
        answers[1] = new Answer("2", "Нет");
        answers[2] = new Answer("3", "Воздержался");
        questions[4] = new Question("3.1", "Утвердить  Годовой  отчет  Общества  за  2016 год, годовой бухгалтерский  баланс и счет прибылей и убытков Общества за 2016 год.", answers);
        return new Voting("1", "GMET_Ежегодное голосование", startTime, endTime, questions, SECURITY);
    }

    private static Voting generateVotingEn(long startTime, long endTime) throws Exception {
        Question[] questions = new Question[5];
        Answer[] answers = new Answer[3];
        answers[0] = new Answer("1", "For");
        answers[1] = new Answer("2", "Against");
        answers[2] = new Answer("3", "Abstain");
        questions[0] = new Question("1.1", "Elect into committee A", answers);
        answers = new Answer[3];
        answers[0] = new Answer("1", "For");
        answers[1] = new Answer("2", "Against");
        answers[2] = new Answer("3", "Abstain");
        questions[1] = new Question("1.2", "Elect into committee B", answers);
        answers = new Answer[3];
        answers[0] = new Answer("1", "For");
        answers[1] = new Answer("2", "Against");
        answers[2] = new Answer("3", "Abstain");
        questions[2] = new Question("2.1", "Elect committee directors.", answers);
        answers = new Answer[3];
        answers[0] = new Answer("2.1.1", "Ivanov");
        answers[1] = new Answer("2.1.2", "Petrov");
        answers[2] = new Answer("2.1.3", "Sidorov");
        questions[3] = new Question("2.1.multi", "Elect committee director members", answers, true, 1);
        answers = new Answer[3];
        answers[0] = new Answer("1", "For");
        answers[1] = new Answer("2", "Against");
        answers[2] = new Answer("3", "Abstain");
        questions[4] = new Question("3.1", "Approve annual document", answers);
        return new Voting("1", "GMET_Annual voting", startTime, endTime, questions, SECURITY);
    }

    private void saveData(ClientFullInfo[] clients, Participant[] participants, String name, Voting voting,
                          int holdersCount, int vmCount, int minutes, boolean generateVotes,
                          boolean generateDisconnect, int disconnectNodes) throws Exception {
        //saving info to appropriate files
        final String dirPath = "/src/main/resources/scenarios";
        FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/participants.json", BaseTestsLauncher.MODULE_NAME, dirPath, name)), mapper.writeValueAsString(participants));
        Iso20022Serializer serializer = new Iso20022Serializer();
        FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/voting.xml", BaseTestsLauncher.MODULE_NAME, dirPath, name)), serializer.serialize(voting));
        StringBuilder vmConfig = new StringBuilder();
        int countByVM = (holdersCount + vmCount - 1) / vmCount;
        int totalCount = 0;
        for (int i = 0; i < vmCount; i++) {
            int count = Math.min(holdersCount - totalCount, countByVM);
            vmConfig.append(String.format("%s=%s%n", i, count));
            totalCount += count;
        }
        FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/vm.txt", BaseTestsLauncher.MODULE_NAME, dirPath, name)), vmConfig.toString());

        //aggregating data from all files and save it to one file
        if (generateDisconnect)
            ThreadLocalRandom.current().ints(1, holdersCount - 1).distinct().limit(disconnectNodes).forEach(i -> clients[i].setWalletOffShedule(generateWalletoffShedule(minutes)));
        StringBuilder nodesConfig = new StringBuilder();
        for (int i = 0; i < holdersCount; i++) {
            ClientFullInfo client = clients[i];
            List<ClientCredentials> credentials = client.getClients().stream().
                map(child -> new ClientCredentials(Integer.toString(child.getId()), Integer.toString(child.getId()))).
                collect(Collectors.toList());
            FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/%s/credentials.json", BaseTestsLauncher.MODULE_NAME, dirPath, name, client.getId())), mapper.writeValueAsString(credentials));
            List<Client> clientsJson = client.getClients().stream().
                map(child -> new Client(Integer.toString(child.getId()), child.getPacketSizeBySecurity(), child.getRole())).
                collect(Collectors.toList());
            FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/%s/clients.json", BaseTestsLauncher.MODULE_NAME, dirPath, name, client.getId())),
                mapper.writeValueAsString(new ClientsOnTime[]{new ClientsOnTime(-20000, clientsJson.toArray(new Client[clientsJson.size()]))}));
            String messages = client.getClients().stream().
                filter(child -> child.getVote() != null).
                map(child -> String.format("%s:%s", randomInt(30, minutes * 60), child.getVote().toString())).
                reduce("", (s1, s2) -> s1 + "\n" + s2);
            FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/%s/messages.txt", BaseTestsLauncher.MODULE_NAME, dirPath, name, client.getId())), generateVotes ? messages : "");
            FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/%s/walletoff_schedule.txt", BaseTestsLauncher.MODULE_NAME, dirPath, name, client.getId())), client.getWalletOffShedule());

            nodesConfig.append(i);
            nodesConfig.append("=");
            nodesConfig.append(mapper.writeValueAsString(new NodeInfo(client.getId() == 0 ? MASTER_PASSWORD : (client.isVictim() ? "victim_password" : ""), client.getId(), Math.max(0, client.getHolderId()), client.getPrivateKey(),
                !client.isHonest() ? "client_password" : null)));
            nodesConfig.append("\n");
        }
        FileUtils.writeStringToFile(new File(String.format("%s/%s/%s/voting.txt", BaseTestsLauncher.MODULE_NAME, dirPath, name)), nodesConfig.toString());
    }

    private String generateWalletoffShedule(int minutes) {
        StringBuilder builder = new StringBuilder();
        int disconnections = randomInt(1, 5);
        int startTime = 30;
        int maxDuration = minutes * 60 / 5;
        for (int i = 0; i < disconnections; i++) {
            int duration = randomInt(1, maxDuration);
            int endTime = Math.min(startTime + duration, minutes * 60 - 30);
            builder.append(String.format("%s-%s", startTime, endTime));
            startTime = endTime + randomInt(1, maxDuration);
            if (startTime > minutes * 60 - 30)
                break;
            if (i < 4)
                builder.append(";");
        }
        return builder.toString();
    }

    private static int randomInt(int baseMinValue, int baseMaxValue) {
        Random random = new Random();
        return baseMinValue + random.nextInt(baseMaxValue - baseMinValue + 1);
    }
}
