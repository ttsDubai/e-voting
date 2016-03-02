/******************************************************************************
 * e-voting system                                                            *
 * Copyright (C) 2016 DSX Technologies Limited.                               *
 *                                                                            *
 * This program is free software; you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation; either version 2 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied                         *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 *                                                                            *
 * You can find copy of the GNU General Public License in LICENSE.txt file    *
 * at the top-level directory of this distribution.                           *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package uk.dsxt.voting.common.domain.dataModel;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.*;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VoteResult {

    @Getter
    private final String votingId;

    @Getter
    private final String holderId;

    @Getter
    private BigDecimal packetSize;

    @Getter
    @Setter
    private VoteResultStatus status;

    @Getter
    private final SortedMap<String, VotedAnswer> answersByKey;

    public VoteResult(String votingId, String holderId) {
        answersByKey = new TreeMap<>();
        this.votingId = votingId;
        this.holderId = holderId;
        packetSize = BigDecimal.ZERO;
        status = VoteResultStatus.OK;
    }

    public VoteResult(String s) {
        answersByKey = new TreeMap<>();
        if (s == null)
            throw new IllegalArgumentException("VoteResult can not be created from null string");
        String[] terms = s.split(",");
        if (terms.length == 1 && s.endsWith(",")) {
            votingId = terms[0];
            holderId = null;
            packetSize = BigDecimal.ZERO;
            return;
        } else if (terms.length < 3)
            throw new IllegalArgumentException(String.format("VoteResult can not be created from string with %d terms (%s)", terms.length, s));
        votingId = terms[0];
        holderId = terms[1].length() == 0 ? null : terms[1];
        packetSize = new BigDecimal(terms[2]);
        for(int i = 3; i < terms.length; i++) {
            VotedAnswer answer = new VotedAnswer(terms[i]);
            answersByKey.put(answer.getKey(), answer);
        }
        status = VoteResultStatus.OK;
    }

    public Collection<VotedAnswer> getAnswers() {
        return answersByKey.values();
    }

    public VotedAnswer getAnswerByKey(String key) {
        return answersByKey.get(key);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(votingId);
        sb.append(',');
        if (holderId != null) {
            sb.append(holderId);
        }
        for(VotedAnswer answer : answersByKey.values()) {
            sb.append(',');
            sb.append(answer);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object otherObject) {
        if (!(otherObject instanceof VoteResult))
            return false;
        VoteResult other = (VoteResult)otherObject;
        if (!votingId.equals(other.getVotingId()))
            return false;
        if ((holderId == null) != (other.getHolderId() == null) || holderId != null && !holderId.equals(other.getHolderId()))
            return false;
        if (packetSize.compareTo(other.getPacketSize()) != 0)
            return false;
        if (answersByKey.size() != other.getAnswers().size())
            return false;
        for(VotedAnswer otherAnswer: other.getAnswers()) {
            VotedAnswer answer = answersByKey.get(otherAnswer.getKey());
            if (answer == null || answer.getVoteAmount().compareTo(otherAnswer.getVoteAmount()) != 0)
                return false;
        }
        return true;
    }

    public void add(VoteResult other) {
        if (other == null)
            return;
        addAnswers(answersByKey, other.getAnswers());
    }

    public VoteResult sum(VoteResult other) {
        SortedMap<String, VotedAnswer> answers = new TreeMap<>(answersByKey);
        addAnswers(answers, other.getAnswers());
        return new VoteResult(votingId, holderId, packetSize.add(other.getPacketSize()), status, answers);
    }

    private void addAnswers(SortedMap<String, VotedAnswer> answers, Collection<VotedAnswer> newAnswers) {
        for(VotedAnswer otherAnswer: newAnswers) {
            VotedAnswer answer = answers.get(otherAnswer.getKey());
            if (answer == null) {
                answers.put(otherAnswer.getKey(), otherAnswer);
            } else {
                answers.put(otherAnswer.getKey(), new VotedAnswer(answer.getQuestionId(), answer.getAnswerId(), answer.getVoteAmount().add(otherAnswer.getVoteAmount())));
            }
        }
    }

    public BigDecimal getSumQuestionAmount(int questionId) {
        return answersByKey.values().stream().filter(a -> a.getQuestionId() == questionId).map(VotedAnswer::getVoteAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
    }

    public String findError(Voting voting) {
        if (packetSize.signum() <= 0)
            return String.format("Result has nonpositive packet size %s", packetSize);
        Map<Integer, BigDecimal> amountsByQuestionId = new HashMap<>();
        for(VotedAnswer answer : answersByKey.values()) {
            if (answer.getVoteAmount().signum() <= 0)
                return String.format("Answer %s has nonpositive amount %s", answer.getKey(), answer.getVoteAmount());
            Optional<Question> question = Arrays.stream(voting.getQuestions()).filter(q -> q.getId() == answer.getQuestionId()).findFirst();
            if (!question.isPresent())
                return String.format("Answer %s has unknown question %d", answer.getKey(), answer.getQuestionId());
            if (!Arrays.stream(question.get().getAnswers()).filter(a -> a.getId() == answer.getAnswerId()).findFirst().isPresent())
                return String.format("Answer %s has unknown answer %d", answer.getKey(), answer.getAnswerId());
            BigDecimal sum = amountsByQuestionId.get(answer.getQuestionId());
            amountsByQuestionId.put(answer.getQuestionId(), sum == null ? answer.getVoteAmount() : sum.add(answer.getVoteAmount()));
        }
        for(Map.Entry<Integer, BigDecimal> questionAmount : amountsByQuestionId.entrySet()) {
            if (questionAmount.getValue().compareTo(packetSize) > 0)
                return String.format("Question %d sum amount %s is more than packet size %s", questionAmount.getKey(), questionAmount.getValue(), packetSize);
        }
        return null;
    }
}