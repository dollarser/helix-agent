from pathlib import Path
import json
archive=Path('scripts/debug/2026-09-09/retired-goal-verifier'); archive.mkdir(exist_ok=True)
names=['GoalArchiveArtifactStore','GoalCompletionVerifier','GoalCriterionAccess','GoalCriterionEditor','GoalCriterionFailures','GoalCriterionVerifier','GoalEvidenceCheck','GoalEvidenceContinue','GoalEvidenceFailure','GoalEvidenceIo','GoalToolArtifactStore','GoalToolEvidenceReader','EditedArtifactContent','ScopedEditedArtifactReader','WrittenArtifactContent']
paths=[Path('app/src/main/kotlin/com/helix/app/goal',n+'.kt') for n in names]
paths += [Path('app/src/main/kotlin/com/helix/app/ui',n+'.kt') for n in ['GoalCriteriaDialog','GoalEvidenceDialog','GoalEvidenceFailureLabel']]
# Retire tests specifically for the removed feature; new model-report tests replace its acceptance contract.
test_names=['GoalArchiveArtifactDeviceTest','ProotEvidenceCancellationTest','ProotGoalReviewUiTest','GoalCriterionMappingTest','GoalCriterionFailuresTest','ScopedEditedArtifactReaderTest','GoalEvidenceFailureDeviceTest','GoalCriterionEditorDeviceTest','GoalWrittenArtifactDeviceTest','GoalEvidenceProcessKillDeviceTest','GoalCriterionVerifierDeviceTest','GoalCompletionDeviceTest','GoalEvidenceContinueDeviceTest','GoalCriterionPersistenceUiTest','GoalToolEvidenceReaderDeviceTest','GoalEvidenceFailureUiTest','GoalCriterionBindingUiTest','GoalEvidenceContinueUiTest','GoalRealCompletionUiTest']
paths += [p for p in Path('app/src').rglob('*.kt') if p.stem in test_names and '/main/' not in str(p)]
for p in paths:
 if p.exists():
  target=archive/(str(p).replace('/','__')+'.txt');target.write_bytes(p.read_bytes());p.unlink()
(archive/'README.md').write_text('# Retired Goal verifier sources\n\nHXA-178 / ADR-0040 replaces this feature at the owner request. These inert source snapshots preserve the old implementation and its tests for historical review. They are not active tests or supported code. New model-report, lifecycle, migration and device tests cover the replacement contract. Never execute these snapshots.\n')
p=Path('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt');s=p.read_text().replace('    var evidenceGoal by remember { mutableStateOf<String?>(null) }\n','')
a=s.index('    if (evidenceGoal != null) {');b=s.index('        GoalEditor(',a);s=s[:a]+'    if (creating || editing != null) {\n'+s[b:]
a=s.index('                        Text(stringResource(R.string.goal_criteria_progress');b=s.index('                        TextButton(',a);s=s[:a]+'                        GoalCriterionDescriptions(row.criteria)\n'+s[b:]
a=s.index('                        TextButton(\n                            onClick = { evidenceGoal');b=s.index('                        TextButton(',a+30);s=s[:a]+s[b:]
a=s.index('                                        if (service.completeGoalFromEvidence(row.id))');b=s.index('                                    } catch',a);s=s[:a]+'''                                        service.continueGoal(row.id, prompt.ifBlank { row.objective })
                                        onContinued()
                                        onDismiss()
'''+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt');s=p.read_text().replace('result.status != "SUCCESS"','result.status != "SUCCEEDED"');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/GoalBlockerResolution.kt');s=p.read_text();a=s.index('            if (outcome == "BLOCKED(EVIDENCE_BINDING_REQUIRED)"');b=s.index('            if (outcome == "BLOCKED(CONTEXT_WINDOW_LIMIT)"',a);s=s[:a]+s[b:];p.write_text(s)
